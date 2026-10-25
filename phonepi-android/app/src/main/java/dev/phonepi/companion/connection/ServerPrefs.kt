package dev.phonepi.companion.connection

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Saved server list + failover settings. Seeds HP (primary) + Box (fallback) once, from 0.3.2 prefs. */
object ServerPrefs {
    private const val PREFS = "phonepi_servers"
    private const val KEY_SERVERS = "servers_json"
    private const val KEY_MIGRATED = "migrated_033"
    private const val KEY_AUTO = "auto_failover"
    private const val KEY_PINNED = "pinned_id"

    data class Snapshot(
        val servers: List<ServerEntry>,
        val autoFailover: Boolean,
        val pinnedId: String?,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): Snapshot {
        TokenVault.migrateIfNeeded(context)
        val p = prefs(context)
        var servers = ServerList.decode(p.getString(KEY_SERVERS, null))
        if (servers == null && !p.getBoolean(KEY_MIGRATED, false)) {
            servers = ServerList.seed(ConnectionPrefs.getLastSuccessful(context))
            persistServers(context, servers)
            p.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
        val hydrated = servers.orEmpty().map { entry ->
            val stored = TokenVault.serverToken(context, entry.id)
            if (stored.isNotBlank()) entry.copy(token = stored) else entry
        }
        return Snapshot(
            servers = hydrated,
            autoFailover = p.getBoolean(KEY_AUTO, true),
            pinnedId = p.getString(KEY_PINNED, null),
        )
    }

    @Synchronized
    fun saveServers(context: Context, servers: List<ServerEntry>) {
        persistServers(context, servers)
        prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun persistServers(context: Context, servers: List<ServerEntry>) {
        for (entry in servers) {
            TokenVault.setServerToken(context, entry.id, entry.token)
        }
        val stripped = servers.map { it.copy(token = "") }
        prefs(context).edit()
            .putString(KEY_SERVERS, ServerList.encode(stripped))
            .apply()
    }

    fun setAutoFailover(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun setPinned(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id == null) remove(KEY_PINNED) else putString(KEY_PINNED, id)
        }.apply()
    }

    /** "Forget saved host": wipe servers and stored tokens. Do not reseed. */
    fun forget(context: Context) {
        TokenVault.clearAll(context)
        prefs(context).edit()
            .clear()
            .putBoolean(KEY_MIGRATED, true)
            .apply()
    }
}

/** What the Connect screen shows about the active server. */
data class ActiveServerInfo(
    val name: String,
    val host: String,
    val port: Int,
    val isPrimary: Boolean,
    val autoFailover: Boolean,
    val pinned: Boolean,
)

object ActiveServer {
    private val _info = MutableStateFlow<ActiveServerInfo?>(null)
    val info: StateFlow<ActiveServerInfo?> = _info.asStateFlow()

    fun update(info: ActiveServerInfo?) {
        _info.value = info
    }
}
