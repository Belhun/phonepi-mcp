package dev.phonepi.companion.connection

import android.content.Context

/** Last host/port that completed a WebSocket handshake. Used for UI prefill and service restarts. */
object ConnectionPrefs {
    private const val PREFS = "phonepi_connection"
    private const val KEY_HOST = "last_success_host"
    private const val KEY_PORT = "last_success_port"
    private const val KEY_WS_TOKEN = "last_ws_token"
    private const val KEY_RECONNECT_ON_BOOT = "reconnect_on_boot"
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 11041

    data class Endpoint(val host: String, val port: Int, val wsToken: String = "")

    fun getLastSuccessful(context: Context): Endpoint? {
        TokenVault.migrateIfNeeded(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        if (host.isEmpty()) return null
        val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        val wsToken = TokenVault.lastToken(context).ifBlank {
            prefs.getString(KEY_WS_TOKEN, null).orEmpty()
        }
        return Endpoint(host, port, wsToken)
    }

    fun saveSuccessfulConnection(context: Context, host: String, port: Int, wsToken: String = "") {
        saveEndpoint(context, host, port, wsToken)
    }

    fun saveEndpoint(context: Context, host: String, port: Int, wsToken: String = "") {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        TokenVault.setLastToken(context, wsToken.trim())
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, trimmed)
            .putInt(KEY_PORT, port)
            .remove(KEY_WS_TOKEN)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun reconnectOnBoot(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RECONNECT_ON_BOOT, true)

    fun setReconnectOnBoot(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RECONNECT_ON_BOOT, enabled)
            .apply()
    }

    fun resolveEndpoint(context: Context, host: String?, port: Int?, wsToken: String? = null): Endpoint {
        if (!host.isNullOrBlank()) {
            return Endpoint(host.trim(), port ?: DEFAULT_PORT, wsToken.orEmpty())
        }
        return getLastSuccessful(context) ?: Endpoint(DEFAULT_HOST, DEFAULT_PORT)
    }
}
