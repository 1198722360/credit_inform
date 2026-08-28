package com.creditinform.app.monitoring

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import com.creditinform.app.data.RelayRepository
import com.creditinform.app.ui.AlertActivity
import kotlin.math.roundToInt

data class UrgentAlarm(
    val id: String,
    val title: String,
    val message: String,
    val detail: String,
    val prominentText: String = "",
    val notificationTitle: String = title,
    val notificationText: String = message,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val alarmVolumePercent: Int = 100,
    val ringtoneUri: String = ""
)

object AlarmController {
    private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1_000L
    private const val AUDIO_STATE_PREFS = "urgent_alarm_audio_state"
    private const val KEY_MODIFIED = "modified"
    private const val KEY_RINGER_MODE = "ringer_mode"
    private const val KEY_ALARM_VOLUME = "alarm_volume"
    private const val KEY_INTERRUPTION_FILTER = "interruption_filter"
    const val RELAY_EVENT_ALERT_PREFIX = "relay-event:"
    const val RELAY_HEALTH_ALERT_ID = "relay-health"
    const val RELAY_CONFIG_ALERT_ID = "relay-config-health"
    private val activeAlerts = linkedMapOf<String, UrgentAlarm>()
    private var player: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedRingerMode: Int? = null
    private var savedAlarmVolume: Int? = null
    private var savedInterruptionFilter: Int? = null

    @Synchronized
    fun triggerUrgent(context: Context, alarm: UrgentAlarm) {
        val appContext = context.applicationContext
        NotificationChannels.create(appContext)
        val isFirstAlert = activeAlerts.isEmpty()
        activeAlerts[alarm.id] = alarm

        if (isFirstAlert) {
            acquireWakeLock(appContext)
            forceAudibleMode(appContext, alarm.alarmVolumePercent)
        } else {
            setAlarmVolume(appContext, alarm.alarmVolumePercent)
        }

        if (alarm.soundEnabled && player == null) startSound(appContext, alarm.ringtoneUri)
        if (alarm.vibrateEnabled) startVibration(appContext)
        postAlertNotification(appContext, alarm)
    }

    @Synchronized
    fun dismiss(context: Context, alertId: String) {
        val removed = activeAlerts.remove(alertId)
        cancelNotification(context, alertId)
        if (alertId.startsWith(RELAY_EVENT_ALERT_PREFIX)) {
            RelayRepository.get(context).acknowledgeLocally(
                alertId.removePrefix(RELAY_EVENT_ALERT_PREFIX)
            )
        }
        if (removed == null) {
            if (activeAlerts.isEmpty()) restoreStoredAudioSettings(context.applicationContext)
            return
        }
        if (activeAlerts.isEmpty()) {
            stopOutputsAndRestore(context.applicationContext)
        } else {
            refreshOutputs(context.applicationContext)
        }
    }

    @Synchronized
    fun dismissAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        activeAlerts.keys.forEach { alertId ->
            manager.cancel(alertId, ALERT_NOTIFICATION_ID)
            manager.cancel(legacyNotificationId(alertId))
        }
        activeAlerts.clear()
        stopOutputsAndRestore(context.applicationContext)
    }

    /** Restores settings left changed if the previous app process was killed during an alert. */
    @Synchronized
    fun restoreIfNeeded(context: Context) {
        if (activeAlerts.isEmpty()) restoreStoredAudioSettings(context.applicationContext)
    }

    private fun refreshOutputs(context: Context) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { context.getSystemService(Vibrator::class.java).cancel() }

        val remaining = activeAlerts.values.toList()
        remaining.maxOfOrNull { it.alarmVolumePercent }?.let { setAlarmVolume(context, it) }
        remaining.lastOrNull { it.soundEnabled }?.let { startSound(context, it.ringtoneUri) }
        if (remaining.any { it.vibrateEnabled }) startVibration(context)
    }

    private fun acquireWakeLock(context: Context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CreditInform:UrgentBalanceAlarm"
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun forceAudibleMode(context: Context, volumePercent: Int) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        savedRingerMode = runCatching { audioManager.ringerMode }.getOrNull()
        savedAlarmVolume = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }.getOrNull()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager.isNotificationPolicyAccessGranted) {
            savedInterruptionFilter = notificationManager.currentInterruptionFilter
        }
        context.getSharedPreferences(AUDIO_STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODIFIED, true)
            .putInt(KEY_RINGER_MODE, savedRingerMode ?: AudioManager.RINGER_MODE_NORMAL)
            .putInt(KEY_ALARM_VOLUME, savedAlarmVolume ?: 0)
            .putInt(KEY_INTERRUPTION_FILTER, savedInterruptionFilter ?: -1)
            .commit()

        if (notificationManager.isNotificationPolicyAccessGranted) {
            runCatching {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
        runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL }
        setAlarmVolume(context, volumePercent)
    }

    private fun setAlarmVolume(context: Context, volumePercent: Int) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val requested = (maxVolume * volumePercent.coerceIn(0, 100) / 100f).roundToInt()
        val audibleVolume = if (volumePercent > 0) requested.coerceAtLeast(1) else 0
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audibleVolume, 0)
        }
    }

    private fun startSound(context: Context, configuredUri: String) {
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (audioFocusRequest == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(alarmAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            runCatching { context.getSystemService(AudioManager::class.java).requestAudioFocus(request) }
            audioFocusRequest = request
        }
        val candidates = listOfNotNull(
            configuredUri.takeIf { it.isNotBlank() }?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ).distinct()
        player = candidates.firstNotNullOfOrNull { uri ->
            var candidate: MediaPlayer? = null
            runCatching {
                MediaPlayer().also { candidate = it }.apply {
                    setAudioAttributes(alarmAttributes)
                    setDataSource(context, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.onFailure {
                runCatching { candidate?.release() }
            }.getOrNull()
        }
    }

    private fun startVibration(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, 900, 250, 900, 250, 1_400, 400),
            intArrayOf(0, 255, 0, 255, 0, 255, 0),
            0
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching { vibrator.vibrate(effect, attributes) }
    }

    private fun stopOutputsAndRestore(context: Context) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        audioFocusRequest?.let { request ->
            runCatching {
                context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
            }
        }
        audioFocusRequest = null
        runCatching { context.getSystemService(Vibrator::class.java).cancel() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null

        restoreStoredAudioSettings(context)
        savedAlarmVolume = null
        savedRingerMode = null
        savedInterruptionFilter = null
    }

    private fun restoreStoredAudioSettings(context: Context) {
        val preferences = context.getSharedPreferences(AUDIO_STATE_PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_MODIFIED, false)) return

        val audioManager = context.getSystemService(AudioManager::class.java)
        val alarmVolume = savedAlarmVolume
            ?: preferences.getInt(KEY_ALARM_VOLUME, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        val ringerMode = savedRingerMode
            ?: preferences.getInt(KEY_RINGER_MODE, audioManager.ringerMode)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, alarmVolume, 0) }
        runCatching { audioManager.ringerMode = ringerMode }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val interruptionFilter = savedInterruptionFilter
            ?: preferences.getInt(KEY_INTERRUPTION_FILTER, -1).takeIf { it >= 0 }
        if (notificationManager.isNotificationPolicyAccessGranted && interruptionFilter != null) {
            runCatching { notificationManager.setInterruptionFilter(interruptionFilter) }
        }
        if (interruptionFilter == null || notificationManager.isNotificationPolicyAccessGranted) {
            preferences.edit().clear().apply()
        }
    }

    private fun postAlertNotification(context: Context, alarm: UrgentAlarm) {
        val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = alertIntentData("open", alarm.id)
            putExtra(AlertActivity.EXTRA_ALERT_ID, alarm.id)
            putExtra(AlertActivity.EXTRA_TITLE, alarm.title)
            putExtra(AlertActivity.EXTRA_MESSAGE, alarm.message)
            putExtra(AlertActivity.EXTRA_DETAIL, alarm.detail)
            putExtra(AlertActivity.EXTRA_PROMINENT_TEXT, alarm.prominentText)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            pendingIntentRequestCode(alarm.id),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = Intent(context, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_DISMISS
            data = alertIntentData("dismiss", alarm.id)
            putExtra(AlertActionReceiver.EXTRA_ALERT_ID, alarm.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            pendingIntentRequestCode(alarm.id),
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, NotificationChannels.ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(alarm.notificationTitle)
            .setContentText(alarm.notificationText)
            .setStyle(Notification.BigTextStyle().bigText(alarm.detail))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "我已知晓", dismissPendingIntent)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(alarm.id, ALERT_NOTIFICATION_ID, notification)
    }

    private fun cancelNotification(context: Context, alertId: String) {
        context.getSystemService(NotificationManager::class.java).run {
            cancel(alertId, ALERT_NOTIFICATION_ID)
            // Remove notifications posted before tags were introduced.
            cancel(legacyNotificationId(alertId))
        }
    }

    private fun alertIntentData(action: String, alertId: String): Uri = Uri.Builder()
        .scheme("creditinform")
        .authority("alarm")
        .appendPath(action)
        .appendPath(alertId)
        .build()

    private fun pendingIntentRequestCode(id: String): Int = legacyNotificationId(id)

    private fun legacyNotificationId(id: String): Int =
        ALERT_NOTIFICATION_ID + (id.hashCode() and 0x0fffffff)

    private const val ALERT_NOTIFICATION_ID = 2_000
}
