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
import kotlinx.coroutines.CancellationException
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            repository.setEnabled(false)
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
        replayPendingEvents(config)
        var consecutiveFailures = 0
        var consecutiveConfigFailures = 0
        var nextHeartbeatAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
        var registered = false
        while (currentCoroutineContext().isActive && !registered) {
            repository.register()
                .onSuccess {
                    registered = true
                    consecutiveFailures = 0
                    nextHeartbeatAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
                    AlarmController.dismiss(this, AlarmController.RELAY_HEALTH_ALERT_ID)
                }
                .onFailure { error ->
                    consecutiveFailures++
                    maybeTriggerHealthAlarm(config, consecutiveFailures, error)
                    delay(RETRY_DELAY_MS)
                }
        }

        while (currentCoroutineContext().isActive) {
            val result = repository.pollOnce(POLL_SECONDS)
            result.onSuccess { poll ->
                try {
                    if (!poll.cursorValid) {
                        consecutiveFailures = 0
                        return@onSuccess
                    }
                    // Persist first. A process killed after this point will replay pending events.
                    repository.processPendingEvents(config, poll) { triggerEvent(config, it) }
                    // Advance only after every relevant event is durably stored and sounded.
                    repository.acknowledgeThrough(config, poll)
                    consecutiveFailures = 0
                    AlarmController.dismiss(this, AlarmController.RELAY_HEALTH_ALERT_ID)
                    repository.refreshMonitors()
                        .onSuccess { result ->
                            val configError = result.config.lastError.takeIf { it.isNotBlank() }
                                ?: "服务端监控配置未加载".takeUnless { result.config.loaded }
                            if (configError == null) {
                                consecutiveConfigFailures = 0
                                AlarmController.dismiss(
                                    this,
                                    AlarmController.RELAY_CONFIG_ALERT_ID
                                )
                            } else {
                                consecutiveConfigFailures++
                                maybeTriggerConfigAlarm(
                                    config,
                                    consecutiveConfigFailures,
                                    configError
                                )
                            }
                        }
                        .onFailure { error ->
                            consecutiveConfigFailures++
                            maybeTriggerConfigAlarm(
                                config,
                                consecutiveConfigFailures,
                                error.readableMessage()
                            )
                        }
                    if (SystemClock.elapsedRealtime() >= nextHeartbeatAt) {
                        repository.heartbeat()
                        nextHeartbeatAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    consecutiveFailures++
                    maybeTriggerHealthAlarm(config, consecutiveFailures, error)
                    delay(RETRY_DELAY_MS)
                }
            }.onFailure { error ->
                consecutiveFailures++
                maybeTriggerHealthAlarm(config, consecutiveFailures, error)
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun replayPendingEvents(config: RelayConfig) {
        repository.pendingEvents.value
            .filter { it.senderId != config.deviceId }
            .forEach { triggerEvent(config, it) }
    }

    private fun triggerEvent(config: RelayConfig, event: RelayAlarmEvent) {
        val monitorName = event.monitorName?.takeIf { it.isNotBlank() }
        val title = when (event.type) {
            RelayAlertType.MANUAL -> "人工紧急报警"
            RelayAlertType.MONITOR_BREACH -> "服务监控报警"
            RelayAlertType.MONITOR_ERROR -> "服务检测异常"
            RelayAlertType.UNKNOWN -> "紧急报警"
        }
        val sender = event.senderName.ifBlank { "对方客户端" }
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
                    append("发送方：$sender")
                    sentAt?.let { append("\n发送时间：$it") }
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

    private fun maybeTriggerHealthAlarm(
        config: RelayConfig,
        consecutiveFailures: Int,
        error: Throwable
    ) {
        if (consecutiveFailures < HEALTH_ALARM_FAILURES) return
        AlarmController.triggerUrgent(
            this,
            UrgentAlarm(
                id = AlarmController.RELAY_HEALTH_ALERT_ID,
                title = "报警通道离线",
                message = "无法连接中转服务器",
                detail = "已连续失败 $consecutiveFailures 次\n${error.readableMessage()}",
                notificationTitle = "报警通道离线",
                notificationText = "中转服务器连续 $consecutiveFailures 次连接失败",
                soundEnabled = config.soundEnabled,
                vibrateEnabled = config.vibrateEnabled,
                alarmVolumePercent = config.alarmVolumePercent,
                ringtoneUri = config.ringtoneUri
            )
        )
    }

    private fun maybeTriggerConfigAlarm(
        config: RelayConfig,
        consecutiveFailures: Int,
        error: String
    ) {
        if (consecutiveFailures < HEALTH_ALARM_FAILURES) return
        AlarmController.triggerUrgent(
            this,
            UrgentAlarm(
                id = AlarmController.RELAY_CONFIG_ALERT_ID,
                title = "服务端监控配置异常",
                message = "定时测活配置未正常运行",
                detail = "已连续异常 $consecutiveFailures 次\n${error.take(300)}",
                notificationTitle = "服务端监控配置异常",
                notificationText = "定时测活配置连续 $consecutiveFailures 次异常",
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
                "服务端在线，${status.onlineClients} 个客户端，$monitorCount 个监控端点"
            RelayConnectionState.CHECKING -> "正在连接报警服务器"
            RelayConnectionState.OFFLINE -> status.lastError.ifBlank { "报警服务器离线" }
            RelayConnectionState.DISABLED -> "报警通信未启用"
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
            .addAction(android.R.drawable.ic_media_pause, "停用通信", disable)
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
        private const val RETRY_DELAY_MS = 5_000L
        private const val POLL_SECONDS = 25
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val HEALTH_ALARM_FAILURES = 3

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
