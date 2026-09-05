package dev.phonepi.companion.connection

import android.content.Context

/** Last host/port that completed a WebSocket handshake. Used for UI prefill and service restarts. */
object ConnectionPrefs {
    private const val PREFS = "phonepi_connection"
    private const val KEY_HOST = "last_success_host"
    private const val KEY_PORT = "last_success_port"
    private const val KEY_WS_TOKEN = "last_ws_token"
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 11041

    data class Endpoint(val host: String, val port: Int, val wsToken: String = "")

    fun getLastSuccessful(context: Context): Endpoint? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        if (host.isEmpty()) return null
        val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        val wsToken = prefs.getString(KEY_WS_TOKEN, null).orEmpty()
        return Endpoint(host, port, wsToken)
    }

    fun saveSuccessfulConnection(context: Context, host: String, port: Int, wsToken: String = "") {
        saveEndpoint(context, host, port, wsToken)
    }

    fun saveEndpoint(context: Context, host: String, port: Int, wsToken: String = "") {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, trimmed)
            .putInt(KEY_PORT, port)
            .putString(KEY_WS_TOKEN, wsToken.trim())
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun resolveEndpoint(context: Context, host: String?, port: Int?, wsToken: String? = null): Endpoint {
        if (!host.isNullOrBlank()) {
            return Endpoint(host.trim(), port ?: DEFAULT_PORT, wsToken.orEmpty())
        }
        return getLastSuccessful(context) ?: Endpoint(DEFAULT_HOST, DEFAULT_PORT)
    }
}
