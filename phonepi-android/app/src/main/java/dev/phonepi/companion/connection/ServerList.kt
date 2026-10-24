package dev.phonepi.companion.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One saved PhonePi server. [primary] marks the server auto-failover tries first.
 * The token is stored the same way the 0.3.2 single-host token was (app-private prefs).
 */
@Serializable
data class ServerEntry(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String = "",
    val primary: Boolean = false,
) {
    fun label(): String = "$name ($host:$port)"
}

object ServerList {
    const val HP_ID = "hp"
    const val BOX_ID = "box"
    const val HP_HOST = "hp1.tailcbcc46.ts.net"
    const val BOX_HOST = "grokbot-box.tailcbcc46.ts.net"
    const val SERVE_PORT = 443

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(servers: List<ServerEntry>): String =
        json.encodeToString(ListSerializer(ServerEntry.serializer()), servers)

    fun decode(raw: String?): List<ServerEntry>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(ListSerializer(ServerEntry.serializer()), raw)
        }.getOrNull()
    }

    /**
     * 0.3.2 → 0.3.3 seed. HP is primary; Box is the host the app already used
     * (or the box Serve name when nothing usable was saved). Token carries over.
     */
    fun seed(last: ConnectionPrefs.Endpoint?): List<ServerEntry> {
        val token = last?.wsToken.orEmpty()
        val lastHost = last?.host?.let { TailscaleNet.canonicalHost(it) }.orEmpty()
        val usable = last != null &&
            lastHost.isNotBlank() &&
            !lastHost.equals(HP_HOST, ignoreCase = true) &&
            lastHost != ConnectionPrefs.DEFAULT_HOST
        val boxHost = if (usable) lastHost else BOX_HOST
        val boxPort = if (usable) last!!.port else SERVE_PORT
        return listOf(
            ServerEntry(HP_ID, "HP", HP_HOST, SERVE_PORT, token, primary = true),
            ServerEntry(BOX_ID, "Box", boxHost, boxPort, token, primary = false),
        )
    }

    /** Primary first, then the rest in saved order. This is the failover order. */
    fun ordered(servers: List<ServerEntry>): List<ServerEntry> {
        val primary = servers.firstOrNull { it.primary } ?: servers.firstOrNull() ?: return emptyList()
        return listOf(primary) + servers.filter { it.id != primary.id }
    }

    fun sameHost(a: String, b: String): Boolean =
        TailscaleNet.canonicalHost(a).equals(TailscaleNet.canonicalHost(b), ignoreCase = true)

    /**
     * Add or update a server from a setup link or the Connect form.
     * A blank [token] keeps the existing token (or borrows one from another saved server,
     * since every PhonePi server shares the same token). [makePrimary] demotes the others.
     */
    fun upsert(
        servers: List<ServerEntry>,
        name: String?,
        host: String,
        port: Int,
        token: String?,
        makePrimary: Boolean,
    ): Pair<List<ServerEntry>, ServerEntry> {
        val cleanHost = TailscaleNet.canonicalHost(host)
        val existing = servers.firstOrNull { sameHost(it.host, cleanHost) }
        val fallbackToken = servers.firstOrNull { it.token.isNotBlank() }?.token.orEmpty()
        val newToken = token?.trim().orEmpty()
        val entry = if (existing != null) {
            existing.copy(
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
                port = port,
                token = newToken.ifEmpty { existing.token.ifEmpty { fallbackToken } },
                primary = existing.primary || makePrimary,
            )
        } else {
            ServerEntry(
                id = uniqueId(servers, cleanHost),
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: shortName(cleanHost),
                host = cleanHost,
                port = port,
                token = newToken.ifEmpty { fallbackToken },
                primary = makePrimary || servers.isEmpty(),
            )
        }
        var out = if (existing != null) {
            servers.map { if (it.id == existing.id) entry else it }
        } else {
            servers + entry
        }
        if (makePrimary) out = out.map { it.copy(primary = it.id == entry.id) }
        // A token given explicitly also fills servers that never had one.
        if (newToken.isNotEmpty()) {
            out = out.map { if (it.token.isBlank()) it.copy(token = newToken) else it }
        }
        return out to out.first { it.id == entry.id }
    }

    private fun shortName(host: String): String = host.substringBefore('.').ifEmpty { host }

    private fun uniqueId(servers: List<ServerEntry>, host: String): String {
        val base = shortName(host).lowercase().filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "server" }
        var id = base
        var n = 2
        while (servers.any { it.id == id }) {
            id = "$base-$n"
            n += 1
        }
        return id
    }
}
