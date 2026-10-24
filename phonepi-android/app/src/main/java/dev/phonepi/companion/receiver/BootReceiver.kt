package dev.phonepi.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.companion.connection.SavedConnectionReconnect
import dev.phonepi.companion.history.LocationCapture
import dev.phonepi.companion.volume.MidnightVolume

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
        SavedConnectionReconnect.startIfSaved(context, SavedConnectionReconnect.Trigger.BOOT)
        MidnightVolume.rescheduleIfEnabled(context)
        LocationCapture.refresh(context)
    }

    companion object {
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
