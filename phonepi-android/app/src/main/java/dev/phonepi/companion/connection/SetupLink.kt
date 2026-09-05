package dev.phonepi.companion.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** phonepi://setup?host=&port=&token= (Odysseus Settings QR). Also accepts wss/ws/https paste. */
object SetupLink {
    const val SCHEME = "phonepi"

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
    private val _pending = MutableStateFlow<ConnectEndpoint.Target?>(null)
    val pending: StateFlow<ConnectEndpoint.Target?> = _pending.asStateFlow()

    fun offer(target: ConnectEndpoint.Target) {
        _pending.value = target
    }

    fun consume() {
        _pending.value = null
    }
}
