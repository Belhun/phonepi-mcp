package dev.phonepi.companion.textsend

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

object InVehicleState {
    private const val PREFS = "phonepi_send_text_vehicle"
    private const val IN_VEHICLE = "in_vehicle"
    private const val AT = "at"
    private const val FRESH_MS = 10 * 60 * 1000L

    fun current(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(IN_VEHICLE, false)) return false
        return now - prefs.getLong(AT, 0L) <= FRESH_MS
    }

    fun record(context: Context, inVehicle: Boolean, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(IN_VEHICLE, inVehicle)
            .putLong(AT, now)
            .apply()
    }
}

object InVehicleMonitor {
    fun start(context: Context) {
        if (!granted(context)) return
        val client = ActivityRecognition.getClient(context)
        client.requestActivityUpdates(60_000L, pending(context))
    }

    fun stop(context: Context) {
        if (!granted(context)) return
        ActivityRecognition.getClient(context).removeActivityUpdates(pending(context))
    }

    private fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun pending(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, InVehicleReceiver::class.java),
            flags,
        )
    }
}

class InVehicleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val activity = result.mostProbableActivity
        val driving = activity.type == DetectedActivity.IN_VEHICLE && activity.confidence >= 50
        InVehicleState.record(context.applicationContext, driving)
    }
}
