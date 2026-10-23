package dev.phonepi.companion.connection

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Lightweight health check for a standby/primary server. Plain HTTPS HEAD on the
 * update manifest path (token in a header), never a second WebSocket, so the phone
 * keeps exactly one live link. Any answer from the PhonePi HTTP handler except an
 * auth error or a 5xx (Tailscale Serve returns 502 when the server process is down)
 * counts as healthy.
 */
object ServerProbe {
    private val client = OkHttpClient.Builder()
        .dns(TailscaleDns)
        .socketFactory(TailscaleSocketFactory())
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun isHealthyStatus(code: Int): Boolean =
        code in 200..499 && code != 401 && code != 403

    /** Blocking. Call from Dispatchers.IO. */
    fun probe(server: ServerEntry): Boolean {
        val target = ConnectEndpoint.parse(server.host, server.port.toString())
        val scheme = if (target.url.startsWith("wss://")) "https" else "http"
        val url = "$scheme://${target.host}:${target.port}/phonepi/update/manifest"
        val builder = Request.Builder().url(url).head()
        if (server.token.isNotBlank()) builder.header("Authorization", "Bearer ${server.token}")
        return try {
            client.newCall(builder.build()).execute().use { isHealthyStatus(it.code) }
        } catch (_: Exception) {
            false
        }
    }
}
