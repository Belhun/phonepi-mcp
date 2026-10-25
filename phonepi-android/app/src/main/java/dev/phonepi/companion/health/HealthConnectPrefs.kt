package dev.phonepi.companion.health

import android.content.Context

object HealthConnectPrefs {
    private const val FILE = "health_connect"
    private const val ENABLED = "enabled"
    private const val PAUSED = "paused"
    private const val RATIONALE_SHOWN = "rationale_shown"
    private const val PENDING_CLEAR = "pending_remote_clear"
    private const val LAST_SYNCED_MS = "last_synced_ms"
    private const val LAST_ERROR = "last_error"

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

    fun rationaleShown(context: Context): Boolean =
        prefs(context).getBoolean(RATIONALE_SHOWN, false)

    fun setRationaleShown(context: Context, value: Boolean) {
        edit(context) { putBoolean(RATIONALE_SHOWN, value) }
    }

    fun pendingRemoteClear(context: Context): Boolean =
        prefs(context).getBoolean(PENDING_CLEAR, false)

    fun setPendingRemoteClear(context: Context, value: Boolean) {
        edit(context) { putBoolean(PENDING_CLEAR, value) }
    }

    fun lastSyncedMs(context: Context): Long =
        prefs(context).getLong(LAST_SYNCED_MS, 0L)

    fun setLastSyncedMs(context: Context, value: Long) {
        edit(context) { putLong(LAST_SYNCED_MS, value) }
    }

    fun lastError(context: Context): String =
        prefs(context).getString(LAST_ERROR, "").orEmpty()

    fun setLastError(context: Context, value: String) {
        edit(context) { putString(LAST_ERROR, value.take(200)) }
    }

    fun canSync(context: Context): Boolean =
        enabled(context) && !paused(context)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(context).edit().apply(block).apply()
    }
}
