package dev.phonepi.companion.privacy

import android.content.Context
import dev.phonepi.companion.health.HealthConnectPrefs
import dev.phonepi.companion.health.HealthSyncScheduler
import dev.phonepi.companion.history.LocationCapture
import dev.phonepi.companion.history.LocationHistoryPrefs
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.presence.PresenceCapture
import dev.phonepi.companion.presence.PresenceEngine
import dev.phonepi.companion.presence.PresencePrefs
import dev.phonepi.companion.usage.UsagePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One switch that pauses location history, presence, usage, and health at once.
 * Resume of those streams stays on the phone UI, not via token commands.
 */
object SharingMaster {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun stopAll(context: Context) {
        val app = context.applicationContext
        LocationHistoryPrefs.setPaused(app, true)
        LocationCapture.stop(app)
        PresencePrefs.setPaused(app, true)
        PresenceCapture.stop(app)
        scope.launch { PresenceEngine.refresh(app) }
        UsagePrefs.setPaused(app, true)
        HealthConnectPrefs.setPaused(app, true)
        HealthSyncScheduler.apply(app)
        ActivityLog.logConnection(
            "Stop all sharing",
            "Paused location history, presence, screen time, and health",
            "info",
        )
    }

    fun anyPaused(context: Context): Boolean =
        LocationHistoryPrefs.paused(context) ||
            PresencePrefs.paused(context) ||
            UsagePrefs.paused(context) ||
            HealthConnectPrefs.paused(context)
}
