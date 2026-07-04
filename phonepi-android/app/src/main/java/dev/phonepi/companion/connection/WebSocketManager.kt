package dev.phonepi.companion.connection

import android.util.Log
import dev.phonepi.companion.protocol.ToolRouter
import dev.phonepi.companion.protocol.WsMessage
import dev.phonepi.companion.log.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "PhonePiWS"

/**
 * Owns the OkHttp WebSocket, the message loop, keepalive replies, and reconnect.
 * The server drives ping; the app replies with a JSON pong, so OkHttp's own ping
 * is disabled to avoid two competing keepalives.
 */
class WebSocketManager(
    private val scope: CoroutineScope,
    private val router: ToolRouter,
    private val onConnected: (host: String, port: Int) -> Unit = { _, _ -> },
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var host = "127.0.0.1"
    private var port = 11041
    private var attempt = 0
    @Volatile private var manualClose = false

    fun connect(host: String, port: Int) {
        this.host = host
        this.port = port
        manualClose = false
        attempt = 0
        open()
    }

    fun disconnect() {
        manualClose = true
        socket?.close(1000, "client disconnect")
        socket = null
        ConnectionController.update(ConnectionState.Disconnected)
    }

    private fun open() {
        ConnectionController.update(ConnectionState.Connecting)
        val request = Request.Builder().url("ws://$host:$port").build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            Log.i(TAG, "connected to ws://$host:$port")
            ActivityLog.logConnection("Connected", "ws://$host:$port", "success")
            onConnected(host, port)
            ConnectionController.update(ConnectionState.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val msg = WsMessage.parse(text)) {
                is WsMessage.Ping -> webSocket.send("""{"type":"pong"}""")
                is WsMessage.Request -> scope.launch {
                    val res = router.dispatch(msg)
                    webSocket.send(res.toJson())
                    Log.d(TAG, "tool ${msg.tool} -> ${msg.requestId}")
                }
                is WsMessage.ServerError -> Log.w(TAG, "server error: ${msg.error}")
                is WsMessage.Unknown -> Log.v(TAG, "ignored frame type=${msg.type}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closed: $code $reason")
            ActivityLog.logConnection("Disconnected", "code=$code $reason", "info")
            if (!manualClose) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "failure: ${t.message}")
            ActivityLog.logConnection("Connection failed", t.message ?: "unknown", "error")
            if (!manualClose) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (attempt >= 10) {
            ConnectionController.update(ConnectionState.Failed("Could not reach server after $attempt attempts"))
            return
        }
        val delayMs = minOf(60_000L, 1_000L * (1L shl attempt.coerceAtMost(6)))
        attempt++
        ConnectionController.update(ConnectionState.Reconnecting(attempt))
        scope.launch {
            delay(delayMs)
            if (!manualClose) open()
        }
    }
}
