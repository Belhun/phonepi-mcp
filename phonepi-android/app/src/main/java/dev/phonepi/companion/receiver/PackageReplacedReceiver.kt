package dev.phonepi.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.companion.connection.SavedConnectionReconnect
import dev.phonepi.companion.history.LocationCapture
import dev.phonepi.companion.presence.PresenceEngine
import dev.phonepi.companion.volume.MidnightVolume

/**
 * After a self-update the process is gone. [Intent.ACTION_MY_PACKAGE_REPLACED]
 * is a documented exemption for starting the connection foreground service.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SavedConnectionReconnect.startIfSaved(
            context,
            SavedConnectionReconnect.Trigger.PACKAGE_REPLACED,
        )
        MidnightVolume.rescheduleIfEnabled(context)
        LocationCapture.refresh(context)
        PresenceEngine.nudge(context)
    }
}
