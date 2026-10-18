package dev.phonepi.companion.history

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocationCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (!LocationAccess.canCapture(context)) return
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                when (intent.action) {
                    LocationCapture.ACTION_LOCATION -> deliverFixes(app, intent)
                    LocationCapture.ACTION_ACTIVITY -> deliverActivity(app, intent)
                    LocationCapture.ACTION_GEOFENCE -> deliverGeofence(app, intent)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun deliverFixes(context: Context, intent: Intent) {
        val result = LocationResult.extractResult(intent) ?: return
        for (location in result.locations) {
            LocationHistoryEngine.onFix(context, location.toFix())
        }
    }

    private suspend fun deliverActivity(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            val hint = when {
                event.activityType == DetectedActivity.IN_VEHICLE &&
                    event.transitionType == com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER ->
                    MotionHint.VEHICLE_START
                event.activityType == DetectedActivity.IN_VEHICLE &&
                    event.transitionType == com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT ->
                    MotionHint.VEHICLE_STOP
                event.activityType == DetectedActivity.STILL &&
                    event.transitionType == com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER ->
                    MotionHint.STILL
                event.activityType == DetectedActivity.WALKING &&
                    event.transitionType == com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER ->
                    MotionHint.VEHICLE_STOP
                else -> null
            } ?: continue
            LocationHistoryEngine.onMotion(context, hint, System.currentTimeMillis())
        }
    }

    private suspend fun deliverGeofence(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        LocationCapture.requestOneFix(context)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
