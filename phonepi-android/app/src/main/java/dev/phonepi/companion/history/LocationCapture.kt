package dev.phonepi.companion.history

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.LocationManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
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
 * Battery-friendly capture. Balanced fused updates on a long interval,
 * activity transitions, a dwell geofence, and a one-shot significant-motion
 * sensor. Nothing here polls the GPS radio.
 */
object LocationCapture {
    const val ACTION_LOCATION = "dev.phonepi.companion.action.LOCATION_FIX"
    const val ACTION_ACTIVITY = "dev.phonepi.companion.action.ACTIVITY"
    const val ACTION_GEOFENCE = "dev.phonepi.companion.action.GEOFENCE"

    private const val IDLE_INTERVAL_MS = 15 * 60 * 1000L
    private const val DRIVE_INTERVAL_MS = 3 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var motionArmed = false

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            val context = armedContext ?: return
            motionArmed = false
            scope.launch {
                requestOneFix(context)
                armSignificantMotion(context)
            }
        }
    }

    @Volatile
    private var armedContext: Context? = null

    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!LocationAccess.canCapture(app)) {
            stop(app)
            return
        }
        armedContext = app
        registerLocation(app, driveActive = false)
        registerActivity(app)
        armSignificantMotion(app)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        armedContext = null
        motionArmed = false
        val sensorManager = app.getSystemService(SensorManager::class.java)
        val motion = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sensorManager != null && motion != null) {
            runCatching { sensorManager.cancelTriggerSensor(motionListener, motion) }
        }
        if (!playServices(app)) {
            val lm = app.getSystemService(LocationManager::class.java)
            runCatching { lm.removeUpdates(locationIntent(app)) }
            return
        }
        runCatching {
            LocationServices.getFusedLocationProviderClient(app)
                .removeLocationUpdates(locationIntent(app))
        }
        runCatching {
            ActivityRecognition.getClient(app).removeActivityTransitionUpdates(activityIntent(app))
        }
        runCatching {
            LocationServices.getGeofencingClient(app).removeGeofences(geofenceIntent(app))
        }
    }

    fun onDriveActive(context: Context, active: Boolean) {
        if (!LocationAccess.canCapture(context)) return
        registerLocation(context.applicationContext, active)
    }

    @SuppressLint("MissingPermission")
    fun armGeofence(context: Context, latitude: Double, longitude: Double) {
        val app = context.applicationContext
        if (!LocationAccess.canCapture(app) || !playServices(app)) return
        if (!LocationAccess.fineGranted(app)) return
        val fence = Geofence.Builder()
            .setRequestId("phonepi-dwell")
            .setCircularRegion(latitude, longitude, 150f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        LocationServices.getGeofencingClient(app)
            .addGeofences(request, geofenceIntent(app))
            .addOnFailureListener { error ->
                ActivityLog.logLocation(
                    "Geofence not armed: ${error.message ?: "location permission"}",
                    "info",
                )
            }
    }

    @SuppressLint("MissingPermission")
    suspend fun requestOneFix(context: Context) {
        val app = context.applicationContext
        if (!LocationAccess.canCapture(app)) return
        if (playServices(app)) {
            val token = CancellationTokenSource()
            val location = runCatching {
                LocationServices.getFusedLocationProviderClient(app)
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token)
                    .awaitTask()
            }.getOrNull()
            if (location != null) {
                LocationHistoryEngine.onFix(app, location.toFix())
            }
            return
        }
        val lm = app.getSystemService(LocationManager::class.java)
        val last = lm.getProviders(true).mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        if (last != null) LocationHistoryEngine.onFix(app, last.toFix())
    }

    @SuppressLint("MissingPermission")
    private fun registerLocation(context: Context, driveActive: Boolean) {
        val interval = if (driveActive) DRIVE_INTERVAL_MS else IDLE_INTERVAL_MS
        if (playServices(context)) {
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
                .setMinUpdateIntervalMillis(interval / 3)
                .setMinUpdateDistanceMeters(80f)
                .setMaxUpdateDelayMillis(interval * 2)
                .setWaitForAccurateLocation(false)
                .build()
            LocationServices.getFusedLocationProviderClient(context)
                .requestLocationUpdates(request, locationIntent(context))
                .addOnFailureListener { error ->
                    ActivityLog.logLocation(
                        "Location updates not started: ${error.message ?: "permission"}",
                        "error",
                    )
                }
            return
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                safeEnabled(lm, LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            safeEnabled(lm, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            safeEnabled(lm, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        runCatching {
            lm.requestLocationUpdates(provider, interval, 80f, locationIntent(context))
        }.onFailure { error ->
            ActivityLog.logLocation(
                "Location updates not started: ${error.message ?: "permission"}",
                "error",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerActivity(context: Context) {
        if (!playServices(context) || !LocationAccess.activityGranted(context)) return
        val transitions = listOf(
            transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        )
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), activityIntent(context))
            .addOnFailureListener { error ->
                ActivityLog.logLocation(
                    "Activity transitions not started: ${error.message ?: "permission"}",
                    "info",
                )
            }
    }

    private fun transition(type: Int, transition: Int): ActivityTransition =
        ActivityTransition.Builder()
            .setActivityType(type)
            .setActivityTransition(transition)
            .build()

    private fun armSignificantMotion(context: Context) {
        if (motionArmed) return
        val sensorManager = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        motionArmed = sensorManager.requestTriggerSensor(motionListener, sensor)
    }

    private fun playServices(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    private fun safeEnabled(lm: LocationManager, provider: String): Boolean =
        try {
            lm.isProviderEnabled(provider)
        } catch (_: Exception) {
            false
        }

    private fun locationIntent(context: Context): PendingIntent =
        pending(context, ACTION_LOCATION, 41)

    private fun activityIntent(context: Context): PendingIntent =
        pending(context, ACTION_ACTIVITY, 42)

    private fun geofenceIntent(context: Context): PendingIntent =
        pending(context, ACTION_GEOFENCE, 43)

    private fun pending(context: Context, action: String, code: Int): PendingIntent {
        val intent = Intent(context, LocationCaptureReceiver::class.java).setAction(action)
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

internal fun android.location.Location.toFix(): LocationFix = LocationFix(
    timeMs = time,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else 100f,
    speedMps = if (hasSpeed()) speed else null,
)
