package dev.phonepi.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.companion.connection.ConnectionPrefs
import dev.phonepi.companion.connection.ConnectionService
import dev.phonepi.companion.log.ActivityLog

/**
 * Optional reconnect after reboot. Off by default in the sense that it only
 * runs when a host was saved and the Connect-screen toggle is on (default on).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT) {
            return
        }
        val app = context.applicationContext
        if (!ConnectionPrefs.reconnectOnBoot(app)) {
            ActivityLog.logConnection("Boot", "Reconnect on boot is off", "info")
            return
        }
        val saved = ConnectionPrefs.getLastSuccessful(app)
        if (saved == null) {
            ActivityLog.logConnection("Boot", "No saved host; not connecting", "info")
            return
        }
        ActivityLog.logConnection("Boot", "Reconnecting to ${saved.host}:${saved.port}", "info")
        ConnectionService.start(app, saved.host, saved.port, saved.wsToken)
    }

    companion object {
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
