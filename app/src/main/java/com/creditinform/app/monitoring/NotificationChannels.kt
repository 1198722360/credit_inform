package com.creditinform.app.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import com.creditinform.app.R

object NotificationChannels {
    const val SERVICE_CHANNEL_ID = "relay_alarm_service"
    const val ALERT_CHANNEL_ID = "relay_urgent_alerts"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            context.getString(R.string.monitoring_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示值守报警通信服务正在运行"
            setSound(null, null)
            enableVibration(false)
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
