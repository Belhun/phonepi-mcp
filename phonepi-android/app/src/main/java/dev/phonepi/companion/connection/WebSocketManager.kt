package dev.phonepi.companion.connection

import android.content.Context
import android.util.Log
import dev.phonepi.companion.history.LocationHistorySync
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.protocol.ToolRouter
import dev.phonepi.companion.protocol.WsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val TAG = "PhonePiWS"
private const val KEEPALIVE_INTERVAL_MS = 20_000L
private const val KEEPALIVE_TIMEOUT_MS = 45_000L
private const val NETWORK_PROBE_MS = 10_000L

/**
 * Owns the OkHttp WebSocket, the message loop, keepalive, and reconnect.
 * The server sends JSON pings and this app answers pong. This app also sends
 * its own ping so a half-open Tailscale socket cannot stay "connected".
 * Reconnect continues forever with capped exponential backoff.
 */
class WebSocketManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val router: ToolRouter,
    private val onConnected: (host: String, port: Int, wsToken: String) -> Unit = { _, _, _ -> },
    private val permissionPayload: () -> String = {
        PhonePermissionReport.toJson(
            PhonePermissionReport.fromFlags(emptyMap()),
        )
    },
    private val onReady: () -> Unit = {},
    private val random: Random = Random.Default,
) {
    private val client = OkHttpClient.Builder()
        .dns(TailscaleDns)
        .socketFactory(TailscaleSocketFactory())
        .protocols(listOf(Protocol.HTTP_1_1))
        .pingInterval(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var host = "127.0.0.1"
    private var port = 11041
    private var wsToken = ""
    private var lastUrl = "ws://127.0.0.1:11041/phonepi"
    private var attempt = 0
    @Volatile private var manualClose = false
    private var reconnectJob: Job? = null
    private var keepaliveJob: Job? = null
    private var networkJob: Job? = null
    private var generation = 0
    private var lastInboundAt = 0L
    private var lastPermissionPayload: String? = null

    fun connect(host: String, port: Int, wsToken: String = "") {
        this.host = host
        this.port = port
        this.wsToken = wsToken
        manualClose = false
        attempt = 0
        reconnectJob?.cancel()
        open()
    }

    fun disconnect() {
        manualClose = true
        reconnectJob?.cancel()
        networkJob?.cancel()
        keepaliveJob?.cancel()
        generation += 1
        val current = socket
        socket = null
        current?.close(1000, "client disconnect")
        ConnectionController.update(ConnectionState.Disconnected)
    }

    /** App came to the foreground. Skip the backoff when the link is down. */
    fun nudge(reason: String = "app foreground") {
        if (manualClose) return
        val current = socket
        if (current != null && ConnectionController.state.value == ConnectionState.Connected) {
            sendPing(current)
            publishPermissions(force = true)
            return
        }
        attempt = 0
        reconnectJob?.cancel()
        open()
        Log.i(TAG, "nudge: $reason")
    }

    /**
     * Network available or capabilities changed.
     * A live socket gets an immediate ping. A dead or waiting socket opens now.
     */
    fun onNetworkChanged(reason: String) {
        if (manualClose) return
        if (ConnectionController.state.value == ConnectionState.Connecting) return
        if (networkJob?.isActive == true) return
        networkJob = scope.launch {
            delay(1_000)
            if (manualClose) return@launch
            val current = socket
            val live = current != null &&
                ConnectionController.state.value == ConnectionState.Connected
            if (!live) {
                if (ConnectionController.state.value !is ConnectionState.Reconnecting) {
                    return@launch
                }
                attempt = 0
                reconnectJob?.cancel()
                open()
                return@launch
            }
            if (!sendPing(current)) {
                tearDown(current, reason)
                return@launch
            }
            val mark = lastInboundAt
            delay(NETWORK_PROBE_MS)
            if (manualClose) return@launch
            if (socket === current && lastInboundAt == mark) {
                tearDown(current, reason)
            }
        }
    }

    fun sendFrame(text: String): Boolean {
        val webSocket = socket ?: return false
        if (ConnectionController.state.value != ConnectionState.Connected) return false
        return try {
            webSocket.send(text)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun publishPermissions(force: Boolean = false) {
        val webSocket = socket ?: return
        if (ConnectionController.state.value != ConnectionState.Connected) return
        val payload = permissionPayload()
        if (!force && payload == lastPermissionPayload) return
        lastPermissionPayload = payload
        try {
            webSocket.send(payload)
        } catch (t: Throwable) {
            Log.w(TAG, "permissions send failed: ${t.message}")
        }
    }

    private fun open() {
        if (manualClose) return
        reconnectJob?.cancel()
        val previous = socket
        socket = null
        generation += 1
        keepaliveJob?.cancel()
        lastPermissionPayload = null
        previous?.let { old ->
            try {
                old.cancel()
            } catch (_: Exception) {
            }
        }
        ConnectionController.update(ConnectionState.Connecting)
        val target = ConnectEndpoint.parse(host, port.toString(), wsToken)
        this.host = target.host
        this.port = target.port
        this.lastUrl = target.url
        Log.i(TAG, "opening $lastUrl (sni=${target.host})")
        ActivityLog.logConnection("Connecting", lastUrl, "info")
        val request = Request.Builder().url(target.url).build()
        val created = client.newWebSocket(request, listener)
        socket = created
    }

    private fun sendPing(webSocket: WebSocket): Boolean {
        return try {
            webSocket.send("""{"type":"ping"}""")
        } catch (t: Throwable) {
            Log.w(TAG, "ping failed: ${t.message}")
            false
        }
    }

    private fun noteInbound(webSocket: WebSocket) {
        if (socket !== webSocket) return
        lastInboundAt = System.currentTimeMillis()
    }

    private fun startKeepalive(webSocket: WebSocket) {
        val gen = generation
        keepaliveJob?.cancel()
        lastInboundAt = System.currentTimeMillis()
        keepaliveJob = scope.launch {
            while (isActive && generation == gen && socket === webSocket) {
                delay(5_000)
                if (socket !== webSocket || generation != gen) return@launch
                val silent = System.currentTimeMillis() - lastInboundAt
                if (silent >= KEEPALIVE_TIMEOUT_MS) {
                    tearDown(webSocket, "no inbound for ${silent / 1000}s")
                    return@launch
                }
                if (silent >= KEEPALIVE_INTERVAL_MS && !sendPing(webSocket)) {
                    tearDown(webSocket, "ping failed")
                    return@launch
                }
            }
        }
    }

    private fun tearDown(webSocket: WebSocket, reason: String) {
        if (manualClose) return
        if (socket !== webSocket) return
        socket = null
        LocationHistorySync.detach()
        keepaliveJob?.cancel()
        try {
            webSocket.close(1000, "dead link")
        } catch (_: Exception) {
        }
        try {
            webSocket.cancel()
        } catch (_: Exception) {
        }
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (manualClose) return
        reconnectJob?.cancel()
        val delayMs = ReconnectSchedule.delayMs(attempt, random.nextDouble())
        attempt += 1
        val shownAttempt = attempt
        Log.i(TAG, "reconnect in ${delayMs}ms ($reason)")
        ActivityLog.logConnection("Reconnecting", reason, "info")
        reconnectJob = scope.launch {
            var left = delayMs
            while (isActive && !manualClose && left > 0L) {
                ConnectionController.update(
                    ConnectionState.Reconnecting(shownAttempt, left, reason),
                )
                val step = minOf(1_000L, left)
                delay(step)
                left -= step
            }
            if (!manualClose) open()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) {
                webSocket.close(1000, "stale")
                return
            }
            attempt = 0
            Log.i(TAG, "connected to $lastUrl")
            ActivityLog.logConnection("Connected", lastUrl, "success")
            onConnected(host, port, wsToken)
            ConnectionController.update(ConnectionState.Connected)
            startKeepalive(webSocket)
            publishPermissions(force = true)
            LocationHistorySync.attach(appContext) { payload ->
                try {
                    webSocket.send(payload)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "location sync send failed: ${t.message}")
                    false
                }
            }
            onReady()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            noteInbound(webSocket)
            when (val msg = WsMessage.parse(text)) {
                is WsMessage.Ping -> webSocket.send("""{"type":"pong"}""")
                is WsMessage.Pong -> Unit
                is WsMessage.Request -> scope.launch {
                    val res = router.dispatch(msg)
                    if (socket !== webSocket) return@launch
                    webSocket.send(res.toJson())
                    Log.d(TAG, "tool ${msg.tool} -> ${msg.requestId}")
                }
                is WsMessage.ServerError -> Log.w(TAG, "server error: ${msg.error}")
                is WsMessage.LocationAck -> LocationHistorySync.onFrame(text)
                is WsMessage.ActivityNote ->
                    ActivityLog.logRemoteAction(msg.title, msg.detail, msg.status)
                is WsMessage.Unknown -> Log.v(TAG, "ignored frame type=${msg.type}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            Log.i(TAG, "closed: $code $reason")
            ActivityLog.logConnection("Disconnected", "code=$code $reason", "info")
            tearDown(webSocket, closeReason(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            Log.w(TAG, "failure: ${t.message}")
            ActivityLog.logConnection("Connection failed", t.message ?: "unknown", "error")
            val why = t.message?.trim().orEmpty().ifEmpty { "connection failed" }
            tearDown(webSocket, why.take(80))
        }
    }

    private fun closeReason(code: Int, reason: String): String {
        val text = reason.trim()
        return if (text.isEmpty()) "connection closed ($code)" else text.take(80)
    }
}
