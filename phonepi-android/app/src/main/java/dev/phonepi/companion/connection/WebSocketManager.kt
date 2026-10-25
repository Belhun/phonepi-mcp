package dev.phonepi.companion.connection

import android.content.Context
import android.util.Log
import dev.phonepi.companion.history.LocationHistorySync
import dev.phonepi.companion.presence.PresenceSync
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.usage.UsageSync
import dev.phonepi.companion.protocol.ToolRouter
import dev.phonepi.companion.protocol.WsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Owns the OkHttp WebSocket, the message loop, keepalive, reconnect, and server failover
 * (see [FailoverMachine]: primary first, fallback after 3 failures or 15 s, re-probe the
 * primary every 3 min while on a fallback). Exactly one WebSocket is live at a time.
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
    private var machine: FailoverMachine? = null
    private var linkUp = false
    private var deadlineJob: Job? = null
    private var attemptTimeoutJob: Job? = null
    private var probeJob: Job? = null

    /** Start (or restart) from the saved server list. Pinned server first, else the primary. */
    fun start(snapshot: ServerPrefs.Snapshot) {
        val now = System.currentTimeMillis()
        val servers = snapshot.servers.ifEmpty {
            listOf(
                ServerEntry(
                    "default",
                    "Default",
                    ConnectionPrefs.DEFAULT_HOST,
                    ConnectionPrefs.DEFAULT_PORT,
                    primary = true,
                ),
            )
        }
        val m = FailoverMachine(servers, snapshot.autoFailover, snapshot.pinnedId)
        machine = m
        val first = m.start(now) ?: return
        setTarget(first)
        manualClose = false
        linkUp = false
        attempt = 0
        reconnectJob?.cancel()
        probeJob?.cancel()
        publishActive()
        ActivityLog.logConnection("Server", "Using ${first.name} (${modeLabel(m)})", "info")
        open()
        armDeadline()
    }

    /** Auto-failover toggle or list edit without forcing a reconnect. */
    fun applySettings(snapshot: ServerPrefs.Snapshot) {
        val m = machine ?: return
        m.updateServers(snapshot.servers, snapshot.autoFailover, snapshot.pinnedId)
        publishActive()
        armDeadline()
    }

    fun connect(host: String, port: Int, wsToken: String = "") {
        machine = null
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
        deadlineJob?.cancel()
        attemptTimeoutJob?.cancel()
        probeJob?.cancel()
        linkUp = false
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
        Log.i(TAG, "opening ${safeUrl()} (sni=${target.host})")
        ActivityLog.logConnection("Connecting", safeUrl(), "info")
        val request = Request.Builder().url(target.url).build()
        val created = client.newWebSocket(request, listener)
        socket = created
        armAttemptTimeout(created)
    }

    /** URL without the ?token= query, for logs and Recent activity. */
    private fun safeUrl(): String = lastUrl.substringBefore('?')

    private fun setTarget(server: ServerEntry) {
        host = server.host
        port = server.port
        wsToken = server.token
    }

    private fun modeLabel(m: FailoverMachine): String = when {
        m.pinnedId != null -> "manual"
        !m.autoFailover -> "auto-failover off"
        m.servers.size < 2 -> "single server"
        else -> "auto-failover on"
    }

    private fun publishActive() {
        val m = machine ?: return ActiveServer.update(null)
        val a = m.active ?: return ActiveServer.update(null)
        ActiveServer.update(
            ActiveServerInfo(
                name = a.name,
                host = a.host,
                port = a.port,
                isPrimary = m.onPrimary,
                autoFailover = m.autoFailover,
                pinned = m.pinnedId != null,
            ),
        )
    }

    /** A handshake that hangs (dropped SYN, stuck TLS) counts as a failure after 10 s. */
    private fun armAttemptTimeout(created: WebSocket) {
        attemptTimeoutJob?.cancel()
        val timeout = machine?.config?.attemptTimeoutMs ?: return
        attemptTimeoutJob = scope.launch {
            delay(timeout)
            if (socket === created && !linkUp && !manualClose) {
                Log.w(TAG, "connect timeout after ${timeout}ms")
                tearDown(created, "connect timeout")
            }
        }
    }

    /** First server of an outage gets a 15 s deadline; when it fires, go to the next server. */
    private fun armDeadline() {
        deadlineJob?.cancel()
        val m = machine ?: return
        val at = m.deadlineAt() ?: return
        deadlineJob = scope.launch {
            val wait = at - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (manualClose || linkUp) return@launch
            val decision = m.onDeadline(System.currentTimeMillis())
            if (decision is FailoverDecision.SwitchTo) applySwitch(decision)
        }
    }

    private fun applySwitch(d: FailoverDecision.SwitchTo) {
        if (manualClose) return
        val from = d.from?.name ?: "?"
        Log.i(TAG, "server switch $from -> ${d.server.name}: ${d.reason}")
        ActivityLog.logConnection(
            "Server switch",
            "$from → ${d.server.name} (${d.reason})",
            if (d.server.primary) "success" else "info",
        )
        reconnectJob?.cancel()
        probeJob?.cancel()
        val old = socket
        if (old != null) {
            socket = null
            generation += 1
            keepaliveJob?.cancel()
            if (linkUp) {
                LocationHistorySync.detach()
                PresenceSync.detach()
                UsageSync.detach()
            }
            try {
                old.close(1000, "switching server")
            } catch (_: Exception) {
            }
            try {
                old.cancel()
            } catch (_: Exception) {
            }
        }
        linkUp = false
        setTarget(d.server)
        publishActive()
        if (d.immediate) {
            attempt = 0
            open()
        } else {
            scheduleReconnect("switching to ${d.server.name}")
        }
        armDeadline()
    }

    /** While on a fallback, check the primary every few minutes over plain HTTPS. */
    private fun startProbeLoop(webSocket: WebSocket) {
        probeJob?.cancel()
        val m = machine ?: return
        if (!m.failoverEnabled || m.onPrimary) return
        probeJob = scope.launch {
            while (isActive && socket === webSocket && !manualClose) {
                delay(30_000)
                if (socket !== webSocket || manualClose) return@launch
                val now = System.currentTimeMillis()
                if (!m.shouldProbePrimary(now)) continue
                val primary = m.primary ?: return@launch
                val healthy = withContext(Dispatchers.IO) { ServerProbe.probe(primary) }
                if (socket !== webSocket || manualClose) return@launch
                Log.i(TAG, "probe ${primary.name}: ${if (healthy) "healthy" else "down"}")
                val decision = m.onProbeResult(healthy, System.currentTimeMillis())
                if (decision is FailoverDecision.SwitchTo) {
                    applySwitch(decision)
                    return@launch
                }
            }
        }
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
        PresenceSync.detach()
        UsageSync.detach()
        keepaliveJob?.cancel()
        attemptTimeoutJob?.cancel()
        probeJob?.cancel()
        val wasUp = linkUp
        linkUp = false
        try {
            webSocket.close(1000, "dead link")
        } catch (_: Exception) {
        }
        try {
            webSocket.cancel()
        } catch (_: Exception) {
        }
        val m = machine
        if (m != null) {
            val now = System.currentTimeMillis()
            if (wasUp) {
                m.onLinkLost(now)
            } else {
                val decision = m.onFailure(now)
                if (decision is FailoverDecision.SwitchTo) {
                    applySwitch(decision)
                    return
                }
            }
        }
        scheduleReconnect(reason)
        if (wasUp) armDeadline()
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
            linkUp = true
            attemptTimeoutJob?.cancel()
            deadlineJob?.cancel()
            machine?.onConnected(System.currentTimeMillis())
            publishActive()
            Log.i(TAG, "connected to ${safeUrl()}")
            ActivityLog.logConnection(
                "Connected",
                machine?.active?.let { "${it.name} · ${safeUrl()}" } ?: safeUrl(),
                "success",
            )
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
            PresenceSync.attach(appContext) { payload ->
                try {
                    webSocket.send(payload)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "presence sync send failed: ${t.message}")
                    false
                }
            }
            UsageSync.attach(appContext) { payload ->
                try {
                    webSocket.send(payload)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "usage sync send failed: ${t.message}")
                    false
                }
            }
            startProbeLoop(webSocket)
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
                is WsMessage.PresenceAck -> PresenceSync.onFrame(text)
                is WsMessage.UsageAck -> UsageSync.onFrame(text)
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
