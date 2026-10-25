package dev.phonepi.companion.usage

import android.content.Context

object UsagePrefs {
    private const val FILE = "screen_usage"
    private const val ENABLED = "enabled"
    private const val PAUSED = "paused"
    private const val PENDING_CLEAR = "pending_remote_clear"

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        edit(context) { putBoolean(ENABLED, value) }
    }

    fun paused(context: Context): Boolean =
        prefs(context).getBoolean(PAUSED, false)

    fun setPaused(context: Context, value: Boolean) {
        edit(context) { putBoolean(PAUSED, value) }
    }

    fun pendingRemoteClear(context: Context): Boolean =
        prefs(context).getBoolean(PENDING_CLEAR, false)

    fun setPendingRemoteClear(context: Context, value: Boolean) {
        edit(context) { putBoolean(PENDING_CLEAR, value) }
    }

    fun canCapture(context: Context): Boolean =
        enabled(context) && !paused(context) && UsageAccess.isGranted(context)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(context).edit().apply(block).apply()
    }
}
