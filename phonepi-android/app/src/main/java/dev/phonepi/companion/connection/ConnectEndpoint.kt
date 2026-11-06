package dev.phonepi.companion.connection

/**
 * Turns the Odysseus UI address (or a leftover host:port pair) into a WebSocket URL.
 *
 * Tailscale Serve uses the UI host on 443 with path /phonepi (wss).
 * Direct loopback / adb reverse uses ws on the UI port (usually 7000).
 *
 * The host in [Target.url] is only an ASCII hostname or an IP literal.
 * Characters OkHttp treats as the end of the host (`@ / \ ? #`) are kept
 * as-is so [requestMatchesTarget] can refuse the built request; they never
 * pass [AllowedHost.isAllowed].
 */
object ConnectEndpoint {
    const val PHONEPI_PATH = "/phonepi"
    const val DEFAULT_DIRECT_PORT = 11041

    data class Target(
        val host: String,
        val port: Int,
        val url: String,
        val wsToken: String = "",
    )

    fun parse(hostRaw: String, portRaw: String, wsToken: String = ""): Target {
        var raw = hostRaw.trim()
        var schemeHint: String? = null
        val lower = raw.lowercase()
        when {
            lower.startsWith("https://") -> {
                schemeHint = "wss"
                raw = raw.substring(8)
            }
            lower.startsWith("http://") -> {
                schemeHint = "ws"
                raw = raw.substring(7)
            }
            lower.startsWith("wss://") -> {
                schemeHint = "wss"
                raw = raw.substring(6)
            }
            lower.startsWith("ws://") -> {
                schemeHint = "ws"
                raw = raw.substring(5)
            }
        }
        raw = raw.trimEnd('/')
        val slash = raw.indexOf('/')
        val hostPort = if (slash >= 0) raw.substring(0, slash) else raw

        val (extractedHost, portFromHost) = splitHostPort(hostPort)
        var host = TailscaleNet.canonicalHost(extractedHost.trim().trimStart('[').trimEnd(']'))
        if ("&" in host) {
            host = host.substringBefore("&").trim()
        }

        val looksTls = schemeHint == "wss" || host.endsWith(".ts.net", ignoreCase = true)
        val typedPort = portRaw.trim().toIntOrNull()
        val port = when {
            typedPort != null && typedPort > 0 && !(looksTls && typedPort == DEFAULT_DIRECT_PORT) -> typedPort
            portFromHost != null -> portFromHost
            looksTls -> 443
            schemeHint == "ws" -> 80
            else -> DEFAULT_DIRECT_PORT
        }
        val tls = schemeHint == "wss" || port == 443 || (schemeHint == null && looksTls)
        val scheme = if (tls) "wss" else "ws"
        val url = "$scheme://${AllowedHost.hostForUrl(host)}:$port$PHONEPI_PATH"
        return Target(host = host, port = port, url = url, wsToken = wsToken)
    }

    /**
     * Bracketed IPv6 (`[fd7a:…]:443`), unbracketed IPv6 (host only), or
     * hostname/IPv4 with an optional `:port`. A numeric last hextet must not
     * be taken as a port.
     */
    internal fun splitHostPort(hostPort: String): Pair<String, Int?> {
        val text = hostPort.trim()
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close > 0) {
                val inner = text.substring(1, close)
                val rest = text.substring(close + 1)
                val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
                return inner to port
            }
        }
        if (text.count { it == ':' } >= 2) {
            return text to null
        }
        val colon = text.lastIndexOf(':')
        if (colon > 0) {
            val maybePort = text.substring(colon + 1).toIntOrNull()
            if (maybePort != null) {
                return text.substring(0, colon) to maybePort
            }
        }
        return text to null
    }
}
