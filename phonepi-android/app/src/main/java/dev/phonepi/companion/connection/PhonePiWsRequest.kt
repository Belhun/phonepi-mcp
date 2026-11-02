package dev.phonepi.companion.connection

import okhttp3.Request

// WebSocket upgrade request. Token goes in Authorization, never in the URL.
// Tailscale Serve forwards this header on wss to MagicDNS.
// Non-allowlisted hosts never get Authorization (deep-link / open-redirect guard).
internal fun phonePiWsRequest(target: ConnectEndpoint.Target): Request {
    val builder = Request.Builder().url(target.url)
    if (AllowedHost.isAllowed(target.host) && target.wsToken.isNotBlank()) {
        builder.header("Authorization", "Bearer ${target.wsToken}")
    }
    return builder.build()
}
