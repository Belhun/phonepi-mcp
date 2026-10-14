package dev.phonepi.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.volume.MidnightVolume
import dev.phonepi.companion.volume.VolumeController

/** Sets every stream to max at local midnight, then arms the next night. */
class MidnightVolumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (!MidnightVolume.enabled(app)) return
        val result = VolumeController.maxAll(app)
        ActivityLog.logConnection(
            "Volume",
            "Midnight max: ${result.changed.joinToString().ifEmpty { "none" }}" +
                if (result.skipped.isEmpty()) "" else "; skipped ${result.skipped.joinToString()}",
            if (result.error == null) "success" else "error",
        )
        MidnightVolume.schedule(app)
    }
}
