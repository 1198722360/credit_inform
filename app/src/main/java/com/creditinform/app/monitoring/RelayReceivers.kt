package com.creditinform.app.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RelayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!RelayService.isEnabled(context)) return

        RelayService.scheduleRestart(context)
        runCatching { RelayService.start(context) }
    }
}

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISMISS) return
        intent.getStringExtra(EXTRA_ALERT_ID)?.let { id ->
            AlarmController.dismiss(context, id)
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.creditinform.app.DISMISS_ALERT"
        const val EXTRA_ALERT_ID = "alert_id"
    }
}

class RelayServiceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_DISABLE) RelayService.disable(context)
    }

    companion object {
        const val ACTION_DISABLE = "com.creditinform.app.DISABLE_RELAY_FROM_NOTIFICATION"
    }
}

class RelayServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RelayService.ACTION_RESTART || !RelayService.isEnabled(context)) return
        runCatching { RelayService.start(context) }
    }
}
