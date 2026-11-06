package dev.phonepi.companion.connection

import okhttp3.Request

// WebSocket upgrade request. Token goes in Authorization, never in the URL.
// Tailscale Serve forwards this header on wss to MagicDNS.
// Non-allowlisted hosts never get Authorization (deep-link / open-redirect guard).
// The request is built first; Authorization is added only when OkHttp's parsed
// host and port match the expected target (OkHttp ends the host at / \ ? #).
internal fun phonePiWsRequest(target: ConnectEndpoint.Target): Request {
    val request = Request.Builder().url(target.url).build()
    return withPhonePiAuthIfHostMatches(request, target.host, target.port, target.wsToken)
        ?: request
}

/**
 * True when OkHttp's parsed host/port are the host and port we intended, not a
 * prefix cut at `@ / \ ? #`. Expected host must itself be a valid literal.
 */
internal fun requestMatchesTarget(
    request: Request,
    expectedHost: String,
    expectedPort: Int,
): Boolean {
    if (!AllowedHost.isValidHostLiteral(expectedHost)) return false
    val want = TailscaleNet.canonicalHost(
        expectedHost.trim().trimStart('[').trimEnd(']'),
    ).lowercase().let { host ->
        if (host.endsWith('.')) host.dropLast(1) else host
    }
    if (want.isEmpty()) return false
    return request.url.host.equals(want, ignoreCase = true) && request.url.port == expectedPort
}

/** Null means refuse: do not add Authorization and do not connect. */
internal fun withPhonePiAuthIfHostMatches(
    request: Request,
    expectedHost: String,
    expectedPort: Int,
    token: String,
): Request? {
    if (!requestMatchesTarget(request, expectedHost, expectedPort)) return null
    if (AllowedHost.isAllowed(expectedHost) && token.isNotBlank()) {
        return request.newBuilder().header("Authorization", "Bearer $token").build()
    }
    return request
}
