package dev.phonepi.companion.history

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object LocationHistoryPrefs {
    private const val FILE = "location_history"
    private const val ENABLED = "enabled"
    private const val PAUSED = "paused"
    private const val PENDING_CLEAR = "pending_remote_clear"

    fun enabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        edit(context) { putBoolean(ENABLED, value) }
    }

    fun paused(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(PAUSED, false)

    fun setPaused(context: Context, value: Boolean) {
        edit(context) { putBoolean(PAUSED, value) }
    }

    fun pendingRemoteClear(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(PENDING_CLEAR, false)

    fun setPendingRemoteClear(context: Context, value: Boolean) {
        edit(context) { putBoolean(PENDING_CLEAR, value) }
    }

    private fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .apply(block)
            .apply()
    }
}

object LocationAccess {
    fun fineGranted(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun coarseGranted(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun backgroundGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return fineGranted(context) || coarseGranted(context)
        return granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun activityGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return granted(context, Manifest.permission.ACTIVITY_RECOGNITION)
    }

    fun canCapture(context: Context): Boolean =
        LocationHistoryPrefs.enabled(context) &&
            !LocationHistoryPrefs.paused(context) &&
            (fineGranted(context) || coarseGranted(context))

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
