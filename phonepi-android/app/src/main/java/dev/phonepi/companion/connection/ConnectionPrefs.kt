package dev.phonepi.companion.connection

import android.content.Context

/** Last host/port that completed a WebSocket handshake. Used for UI prefill and service restarts. */
object ConnectionPrefs {
    private const val PREFS = "phonepi_connection"
    private const val KEY_HOST = "last_success_host"
    private const val KEY_PORT = "last_success_port"
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 11041

    data class Endpoint(val host: String, val port: Int)

    fun getLastSuccessful(context: Context): Endpoint? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        if (host.isEmpty()) return null
        val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        return Endpoint(host, port)
    }

    fun saveSuccessfulConnection(context: Context, host: String, port: Int) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, trimmed)
            .putInt(KEY_PORT, port)
            .apply()
    }

    fun resolveEndpoint(context: Context, host: String?, port: Int?): Endpoint {
        if (!host.isNullOrBlank()) {
            return Endpoint(host.trim(), port ?: DEFAULT_PORT)
        }
        return getLastSuccessful(context) ?: Endpoint(DEFAULT_HOST, DEFAULT_PORT)
    }
}
