package dev.phonepi.companion.presence

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.phonepi.companion.log.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Presence geofences (up to 100), activity transitions, and a 3-minute
 * inexact check that covers geofence lag. Uses a separate PendingIntent
 * from location-history so the dwell fence stays put.
 */
object PresenceCapture {
    const val ACTION_ACTIVITY = "dev.phonepi.companion.action.PRESENCE_ACTIVITY"
    const val ACTION_GEOFENCE = "dev.phonepi.companion.action.PRESENCE_GEOFENCE"
    const val ACTION_CHECK = "dev.phonepi.companion.action.PRESENCE_CHECK"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(context: Context, places: List<PresencePlace> = emptyList()) {
        val app = context.applicationContext
        if (!PresenceAccess.canCapture(app)) {
            stop(app)
            return
        }
        registerActivity(app)
        applyGeofences(app, places)
        scheduleCheck(app)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        cancelCheck(app)
        if (!playServices(app)) return
        runCatching {
            ActivityRecognition.getClient(app).removeActivityTransitionUpdates(activityIntent(app))
        }
        runCatching {
            LocationServices.getGeofencingClient(app).removeGeofences(geofenceIntent(app))
        }
    }

    @SuppressLint("MissingPermission")
    fun applyGeofences(context: Context, places: List<PresencePlace>) {
        val app = context.applicationContext
        if (!playServices(app) || !PresenceAccess.fineGranted(app)) return
        val fences = PresenceLogic.geofencePlaces(places)
        val client = LocationServices.getGeofencingClient(app)
        val pending = geofenceIntent(app)
        client.removeGeofences(pending).addOnCompleteListener {
            if (fences.isEmpty() || !PresenceAccess.canCapture(app)) return@addOnCompleteListener
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(
                    fences.map { place ->
                        Geofence.Builder()
                            .setRequestId(place.geofenceId())
                            .setCircularRegion(place.latitude, place.longitude, place.radiusMeters)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(
                                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
                            )
                            .build()
                    },
                )
                .build()
            client.addGeofences(request, pending).addOnFailureListener { error ->
                ActivityLog.logPresence(
                    "Presence geofences not armed: ${error.message ?: "location permission"}",
                    "info",
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun requestOneFix(context: Context) {
        val app = context.applicationContext
        if (!PresenceAccess.canCapture(app)) return
        scope.launch {
            val location = currentFix(app) ?: return@launch
            PresenceEngine.onFix(
                app,
                location.latitude,
                location.longitude,
                location.accuracy.toDouble(),
                source = "periodic",
            )
        }
    }

    fun scheduleCheck(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java)
        val trigger = SystemClock.elapsedRealtime() + PRESENCE_CHECK_INTERVAL_MS
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, checkIntent(app))
    }

    fun cancelCheck(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(checkIntent(app))
    }

    @SuppressLint("MissingPermission")
    private fun registerActivity(context: Context) {
        if (!playServices(context) || !PresenceAccess.activityGranted(context)) return
        val transitions = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
        ).flatMap { type ->
            listOf(
                transition(type, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                transition(type, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            )
        }
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), activityIntent(context))
            .addOnFailureListener { error ->
                ActivityLog.logPresence(
                    "Presence activity not started: ${error.message ?: "permission"}",
                    "info",
                )
            }
    }

    private fun transition(type: Int, transition: Int): ActivityTransition =
        ActivityTransition.Builder()
            .setActivityType(type)
            .setActivityTransition(transition)
            .build()

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(context: Context): android.location.Location? {
        if (!playServices(context)) return null
        val token = CancellationTokenSource()
        return runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token)
                .awaitTask()
        }.getOrNull()
    }

    private fun playServices(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    private fun activityIntent(context: Context): PendingIntent =
        pending(context, ACTION_ACTIVITY, 51)

    private fun geofenceIntent(context: Context): PendingIntent =
        pending(context, ACTION_GEOFENCE, 52)

    private fun checkIntent(context: Context): PendingIntent =
        pending(context, ACTION_CHECK, 53)

    private fun pending(context: Context, action: String, code: Int): PendingIntent {
        val intent = Intent(context, PresenceReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, code, intent, flags)
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { if (cont.isActive) cont.resume(null) }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }
