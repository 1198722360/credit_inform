package com.creditinform.app.monitoring

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.creditinform.app.MainActivity
import com.creditinform.app.R
import com.creditinform.app.data.RelayAlarmEvent
import com.creditinform.app.data.RelayAlertType
import com.creditinform.app.data.RelayConfig
import com.creditinform.app.data.RelayConnectionState
import com.creditinform.app.data.RelayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat

class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: RelayRepository
    private var storageAlarmRaised = false

    override fun onCreate() {
        super.onCreate()
        repository = RelayRepository.get(this)
        NotificationChannels.create(this)
        AlarmController.restoreIfNeeded(this)
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification())

        scope.launch {
            repository.config.collectLatest { config ->
                updateServiceNotification()
                if (config.enabled && config.isComplete) {
                    scheduleRestart(this@RelayService)
                    runRelayLoop(config)
                } else {
                    cancelScheduledRestart(this@RelayService)
                    stopSelf()
                }
            }
        }
        scope.launch { repository.status.collect { updateServiceNotification() } }
        scope.launch { repository.monitorStatuses.collect { updateServiceNotification() } }
        scope.launch {
            repository.pendingEvents.collect { events ->
                events.forEach { event ->
                    if (!AlarmController.isActive(AlarmController.RELAY_EVENT_ALERT_PREFIX + event.acknowledgementKey)) {
                        triggerEvent(repository.config.value, event)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            repository.setEnabled(false)
            AlarmController.dismissAll(this)
            cancelScheduledRestart(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        scheduleRestart(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (repository.config.value.let { it.enabled && it.isComplete }) {
            scheduleRestart(this, RESTART_DELAY_MS)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::repository.isInitialized &&
            repository.config.value.let { it.enabled && it.isComplete }
        ) {
            scheduleRestart(this, RESTART_DELAY_MS)
        }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runRelayLoop(config: RelayConfig) {
        while (currentCoroutineContext().isActive) {
            scheduleRestart(this@RelayService)
            repository.checkDueMonitors().onSuccess {
                if (storageAlarmRaised && repository.status.value.state == RelayConnectionState.ONLINE) {
                    storageAlarmRaised = false
                    AlarmController.dismiss(this, AlarmController.RELAY_CONFIG_ALERT_ID)
                }
            }.onFailure { error ->
                if (repository.config.value.enabled) {
                    val id = AlarmController.RELAY_CONFIG_ALERT_ID
                    if (!storageAlarmRaised) AlarmController.triggerUrgent(this,
                        UrgentAlarm(id, "余额监控连接异常", "无法从 sub2api 拉取余额", error.message.orEmpty(),
                            soundEnabled = config.soundEnabled, vibrateEnabled = config.vibrateEnabled,
                            alarmVolumePercent = config.alarmVolumePercent, ringtoneUri = config.ringtoneUri))
                    storageAlarmRaised = true
                }
            }
            delay(1_000)
        }
    }

    private fun triggerEvent(config: RelayConfig, event: RelayAlarmEvent) = synchronized(AlarmController) {
        if (!repository.config.value.enabled || repository.pendingEvents.value.none { it.acknowledgementKey == event.acknowledgementKey }) return
        val monitorName = event.monitorName?.takeIf { it.isNotBlank() }
        val title = when (event.type) {
            RelayAlertType.MANUAL -> "人工紧急报警"
            RelayAlertType.MONITOR_BREACH -> "服务监控报警"
            RelayAlertType.MONITOR_ERROR -> "服务检测异常"
            RelayAlertType.UNKNOWN -> "紧急报警"
        }
        val sender = event.senderName.ifBlank { "本机" }
        val message = when {
            monitorName != null -> "$monitorName：${event.message}"
            else -> "$sender：${event.message}"
        }
        val sentAt = event.createdAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(it)
        }
        AlarmController.triggerUrgent(
            this,
            UrgentAlarm(
                id = AlarmController.RELAY_EVENT_ALERT_PREFIX + event.acknowledgementKey,
                title = title,
                message = message,
                detail = buildString {
                    append("来源：$sender")
                    sentAt?.let { append("\n检测时间：$it") }
                },
                notificationTitle = "$title：$sender",
                notificationText = event.message,
                soundEnabled = config.soundEnabled,
                vibrateEnabled = config.vibrateEnabled,
                alarmVolumePercent = config.alarmVolumePercent,
                ringtoneUri = config.ringtoneUri
            )
        )
    }

    private fun updateServiceNotification() {
        if (!::repository.isInitialized) return
        getSystemService(NotificationManager::class.java).notify(
            SERVICE_NOTIFICATION_ID,
            serviceNotification()
        )
    }

    private fun serviceNotification(): Notification {
        val config = repository.config.value
        val status = repository.status.value
        val monitorCount = repository.monitorStatuses.value.count { it.enabled }
        val detail = when (status.state) {
            RelayConnectionState.ONLINE ->
                "正在监控 $monitorCount 个服务端余额"
            RelayConnectionState.CHECKING -> "正在连接 sub2api"
            RelayConnectionState.OFFLINE -> status.lastError.ifBlank { "余额服务连接异常" }
            RelayConnectionState.DISABLED -> "本机监控未启用"
        }
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disable = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, RelayServiceActionReceiver::class.java).apply {
                action = RelayServiceActionReceiver.ACTION_DISABLE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NotificationChannels.SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("值守报警正在运行")
            .setContentText(detail.take(120))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "停止监控", disable)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun Throwable.readableMessage(): String =
        message?.take(300)?.ifBlank { null } ?: javaClass.simpleName

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1_001
        private const val ACTION_DISABLE = "com.creditinform.app.DISABLE_RELAY"
        const val ACTION_RESTART = "com.creditinform.app.RESTART_RELAY"
        private const val RESTART_REQUEST_CODE = 1_002
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val RESTART_DELAY_MS = 2_000L

        fun sync(context: Context) {
            val appContext = context.applicationContext
            val config = RelayRepository.get(appContext).config.value
            if (config.enabled && config.isComplete) start(appContext) else stop(appContext)
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            val config = RelayRepository.get(appContext).config.value
            if (!config.enabled || !config.isComplete) return
            scheduleRestart(appContext)
            appContext.startForegroundService(Intent(appContext, RelayService::class.java))
        }

        fun disable(context: Context) {
            val appContext = context.applicationContext
            RelayRepository.get(appContext).setEnabled(false)
            AlarmController.dismissAll(appContext)
            cancelScheduledRestart(appContext)
            appContext.stopService(Intent(appContext, RelayService::class.java))
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            cancelScheduledRestart(appContext)
            appContext.stopService(Intent(appContext, RelayService::class.java))
        }

        fun isEnabled(context: Context): Boolean =
            RelayRepository.get(context.applicationContext).config.value.let {
                it.enabled && it.isComplete
            }

        fun scheduleRestart(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val appContext = context.applicationContext
            if (!isEnabled(appContext)) return
            val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            val pendingIntent = restartPendingIntent(appContext)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
            val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canUseExact) {
                runCatching {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }.onFailure {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        }

        fun cancelScheduledRestart(context: Context) {
            val pendingIntent = restartPendingIntent(context.applicationContext)
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
            pendingIntent.cancel()
        }

        private fun restartPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                RESTART_REQUEST_CODE,
                Intent(context, RelayServiceRestartReceiver::class.java).apply {
                    action = ACTION_RESTART
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
