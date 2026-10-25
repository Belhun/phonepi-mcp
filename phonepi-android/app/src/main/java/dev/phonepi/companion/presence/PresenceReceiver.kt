package dev.phonepi.companion.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (!PresenceAccess.canCapture(context) && intent.action != PresenceCapture.ACTION_CHECK) {
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                when (intent.action) {
                    PresenceCapture.ACTION_ACTIVITY -> deliverActivity(app, intent)
                    PresenceCapture.ACTION_GEOFENCE -> deliverGeofence(app, intent)
                    PresenceCapture.ACTION_CHECK -> {
                        if (PresenceAccess.canCapture(app)) {
                            PresenceCapture.requestOneFix(app)
                            PresenceCapture.scheduleCheck(app)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun deliverActivity(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val last = result.transitionEvents.maxByOrNull { it.elapsedRealTimeNanos } ?: return
        PresenceEngine.onActivity(context, PresenceLogic.activityName(last.activityType))
    }

    private suspend fun deliverGeofence(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
        val enter = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val leave = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        if (!enter && !leave) return
        PresenceEngine.onGeofence(context, ids, enter = enter)
        val loc = event.triggeringLocation
        if (loc != null) {
            PresenceEngine.onFix(
                context,
                loc.latitude,
                loc.longitude,
                if (loc.hasAccuracy()) loc.accuracy.toDouble() else 100.0,
                source = "geofence",
            )
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
