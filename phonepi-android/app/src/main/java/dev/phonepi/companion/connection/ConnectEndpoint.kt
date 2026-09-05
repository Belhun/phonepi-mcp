package dev.phonepi.companion.connection

/**
 * Turns the Odysseus UI address (or a leftover host:port pair) into a WebSocket URL.
 *
 * Tailscale Serve uses the UI host on 443 with path /phonepi (wss).
 * Direct loopback / adb reverse uses ws on the UI port (usually 7000).
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

        var host = hostPort
        var portFromHost: Int? = null
        val colon = hostPort.lastIndexOf(':')
        if (colon > 0) {
            val maybePort = hostPort.substring(colon + 1).toIntOrNull()
            if (maybePort != null) {
                portFromHost = maybePort
                host = hostPort.substring(0, colon)
            }
        }
        host = TailscaleNet.canonicalHost(host.trim().trimStart('[').trimEnd(']'))
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
        val base = "$scheme://$host:$port$PHONEPI_PATH"
        val url = if (wsToken.isBlank()) {
            base
        } else {
            "$base?token=${java.net.URLEncoder.encode(wsToken, Charsets.UTF_8.name())}"
        }
        return Target(host = host, port = port, url = url, wsToken = wsToken)
    }
}
