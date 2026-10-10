package com.creditinform.app.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import com.creditinform.app.R

object NotificationChannels {
    /**
     * A new channel is used for the foreground service so devices that hid the
     * previously-created LOW channel can apply the visible DEFAULT importance.
     * The old channel is intentionally left untouched during upgrades.
     */
    const val SERVICE_CHANNEL_ID = "relay_alarm_service_v2"
    const val ALERT_CHANNEL_ID = "relay_urgent_alerts"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            context.getString(R.string.monitoring_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "显示值守报警通信服务正在运行"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "人工报警、服务端监控报警或通信离线时显示全屏紧急提醒"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            // Sound and vibration are driven directly so the configured volume is respected.
            setSound(null, alarmAttributes)
            enableVibration(false)
        }

        manager.createNotificationChannels(listOf(serviceChannel, alertChannel))
    }
}
