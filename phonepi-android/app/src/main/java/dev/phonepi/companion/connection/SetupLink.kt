package dev.phonepi.companion.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A parsed setup link. [name] and [primary] come from the 0.3.3 `name=` / `primary=true`
 * params. A blank target token means "keep the token already saved".
 */
data class SetupRequest(
    val target: ConnectEndpoint.Target,
    val name: String? = null,
    val primary: Boolean = false,
)

/**
 * phonepi://setup?host=&port=&token=&name=&primary=true (Odysseus Settings QR).
 * Also accepts wss/ws/https paste. `token` is optional: when missing, the saved token is kept.
 */
object SetupLink {
    const val SCHEME = "phonepi"

    fun parseRequest(raw: String): SetupRequest? {
        val target = parse(raw) ?: return null
        val q = try {
            val uri = URI(raw.trim())
            if (uri.scheme?.lowercase() in setOf(SCHEME, "intent")) queryMap(uri.rawQuery) else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val name = q["name"]?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
        val primary = q["primary"]?.trim()?.lowercase() in setOf("true", "1", "yes")
        return SetupRequest(target, name, primary)
    }

    fun parse(raw: String): ConnectEndpoint.Target? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            parseUri(URI(text))
        } catch (_: Exception) {
            ConnectEndpoint.parse(text, "")
        }
    }

    private fun parseUri(uri: URI): ConnectEndpoint.Target? {
        val scheme = uri.scheme?.lowercase().orEmpty()
        when (scheme) {
            SCHEME -> {
                val q = queryMap(uri.rawQuery)
                val url = q["url"]
                if (!url.isNullOrBlank()) {
                    return ConnectEndpoint.parse(url, q["port"].orEmpty(), q["token"].orEmpty())
                }
                val hostRaw = q["host"] ?: return null
                val recovered = recoverHostPort(hostRaw, q["port"] ?: "443")
                return ConnectEndpoint.parse(recovered.first, recovered.second, q["token"].orEmpty())
            }
            "ws", "wss", "http", "https" -> return ConnectEndpoint.parse(uri.toString(), "")
            "intent" -> {
                // intent://setup?host=...#Intent;scheme=phonepi;end
                val q = queryMap(uri.rawQuery)
                val hostRaw = q["host"] ?: return null
                val recovered = recoverHostPort(hostRaw, q["port"] ?: "443")
                return ConnectEndpoint.parse(recovered.first, recovered.second, q["token"].orEmpty())
            }
            else -> return null
        }
    }

    fun queryMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in raw.split("&")) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val key = decode(part.substring(0, i))
            val value = decode(part.substring(i + 1))
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    /** PowerShell/adb sometimes glue "&port=" into the host query value. */
    fun recoverHostPort(hostRaw: String, portRaw: String): Pair<String, String> {
        var host = hostRaw.trim()
        var port = portRaw.trim().ifEmpty { "443" }
        if ("&" in host) {
            val extra = host.substringAfter("&")
            host = host.substringBefore("&")
            val extraPort = queryMap(extra)["port"]
            if (!extraPort.isNullOrBlank()) port = extraPort
        }
        return host to port
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
}

object SetupBus {
    private val _pending = MutableStateFlow<SetupRequest?>(null)
    val pending: StateFlow<SetupRequest?> = _pending.asStateFlow()

    fun offer(target: SetupRequest) {
        _pending.value = target
    }

    fun consume() {
        _pending.value = null
    }
}
