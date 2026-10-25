package dev.phonepi.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.phonepi.companion.connection.ActiveServer
import dev.phonepi.companion.connection.ConnectionController
import dev.phonepi.companion.connection.ConnectionPrefs
import dev.phonepi.companion.connection.ConnectionService
import dev.phonepi.companion.connection.ServerList
import dev.phonepi.companion.connection.ServerPrefs
import dev.phonepi.companion.connection.SetupBus
import dev.phonepi.companion.connection.statusLabel
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.privacy.PhonePiQuiet
import dev.phonepi.companion.privacy.SharingMaster

@Composable
fun ConnectScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by ConnectionController.state.collectAsState()
    val setup by SetupBus.pending.collectAsState()

    val saved = remember { ConnectionPrefs.getLastSuccessful(context.applicationContext) }
    var host by remember {
        mutableStateOf(saved?.host ?: ConnectionPrefs.DEFAULT_HOST)
    }
    var port by remember {
        mutableStateOf((saved?.port ?: ConnectionPrefs.DEFAULT_PORT).toString())
    }
    var wsToken by remember { mutableStateOf(saved?.wsToken.orEmpty()) }
    var reconnectOnBoot by remember {
        mutableStateOf(ConnectionPrefs.reconnectOnBoot(context.applicationContext))
    }
    val active by ActiveServer.info.collectAsState()
    var serverSnap by remember { mutableStateOf(ServerPrefs.load(context.applicationContext)) }
    var sharingStopped by remember { mutableStateOf(SharingMaster.allPaused(context.applicationContext)) }
    var quietOn by remember { mutableStateOf(PhonePiQuiet.isActive(context.applicationContext)) }
    fun reloadServers() {
        serverSnap = ServerPrefs.load(context.applicationContext)
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The link works without it; only the status notification is affected. */ }

    fun ensureNotifThen(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        block()
    }

    fun startLink() {
        ensureNotifThen { ConnectionService.start(context) }
    }

    /**
     * Save [nextHost] into the Servers list (blank token keeps the saved one).
     * makePrimary=true → it becomes the primary and auto-failover picks the order.
     * Otherwise it is pinned (manual pick, same as connecting to one host in 0.3.2).
     */
    fun saveAndConnect(
        nextHost: String,
        nextPort: String,
        nextToken: String,
        name: String? = null,
        makePrimary: Boolean = false,
    ) {
        val app = context.applicationContext
        val trimmed = nextHost.trim()
        if (trimmed.isEmpty()) return
        val (list, entry) = ServerList.upsert(
            ServerPrefs.load(app).servers,
            name,
            trimmed,
            nextPort.toIntOrNull() ?: ConnectionPrefs.DEFAULT_PORT,
            nextToken,
            makePrimary,
        )
        ServerPrefs.saveServers(app, list)
        ServerPrefs.setPinned(app, if (makePrimary) null else entry.id)
        ActivityLog.logConnection(
            "Server saved",
            "${entry.name} (${entry.host}:${entry.port})" +
                if (makePrimary) " is now primary" else " (manual pick)",
            "info",
        )
        reloadServers()
        startLink()
    }

    fun switchTo(id: String?) {
        val app = context.applicationContext
        ServerPrefs.setPinned(app, id)
        val name = id?.let { pid -> serverSnap.servers.firstOrNull { it.id == pid }?.name }
        ActivityLog.logConnection(
            "Server switch",
            if (name != null) "Manual: $name" else "Manual: back to automatic (primary first)",
            "info",
        )
        reloadServers()
        startLink()
    }

    LaunchedEffect(setup) {
        val request = setup ?: return@LaunchedEffect
        val target = request.target
        host = target.host
        port = target.port.toString()
        saveAndConnect(
            target.host,
            target.port.toString(),
            target.wsToken,
            name = request.name,
            makePrimary = request.primary,
        )
        SetupBus.consume()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Text("PhonePi companion", style = MaterialTheme.typography.headlineSmall)
            Text("Status: ${state.statusLabel()}", style = MaterialTheme.typography.bodyLarge)
            val activeLine = active?.let { a ->
                val role = if (a.isPrimary) "primary" else "fallback"
                val mode = when {
                    a.pinned -> "manual"
                    a.autoFailover -> "auto-failover on"
                    else -> "auto-failover off"
                }
                "Server: ${a.name} ($role) · $mode"
            } ?: "Server: not connected"
            Text(activeLine, style = MaterialTheme.typography.bodyLarge)

            if (serverSnap.servers.isNotEmpty()) {
                Text("Switch server", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val autoSelected = serverSnap.pinnedId == null
                    if (autoSelected) {
                        Button(onClick = { switchTo(null) }) { Text("Auto") }
                    } else {
                        OutlinedButton(onClick = { switchTo(null) }) { Text("Auto") }
                    }
                    for (server in ServerList.ordered(serverSnap.servers)) {
                        if (serverSnap.pinnedId == server.id) {
                            Button(onClick = { switchTo(server.id) }) { Text(server.name) }
                        } else {
                            OutlinedButton(onClick = { switchTo(server.id) }) { Text(server.name) }
                        }
                    }
                }
                for (server in ServerList.ordered(serverSnap.servers)) {
                    Text(
                        (if (server.primary) "★ " else "• ") + server.label() +
                            if (server.primary) " — primary" else " — fallback",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Automatic failover (primary first, fallback after ~15 s)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = serverSnap.autoFailover,
                        onCheckedChange = { enabled ->
                            val app = context.applicationContext
                            ServerPrefs.setAutoFailover(app, enabled)
                            ActivityLog.logConnection(
                                "Auto-failover",
                                if (enabled) "On" else "Off",
                                "info",
                            )
                            reloadServers()
                            ConnectionService.applySettingsIfRunning(app)
                        },
                    )
                }
            }

            Text(
                "Scan the Odysseus Settings → Phone QR, or paste the Tailscale host. " +
                    "Serve uses port 443. Connect saves the host to Servers and uses it (manual). " +
                    "Tap Auto to go back to primary-first failover. " +
                    "Disconnect keeps the saved servers; Forget saved host wipes them.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = host,
                onValueChange = { input ->
                    host = input
                    val lower = input.lowercase()
                    if (lower.contains("https://") || lower.contains(".ts.net")) {
                        if (port == "11041" || port.isEmpty()) {
                            port = "443"
                        }
                    }
                },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { input -> port = input.filter { it.isDigit() } },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = wsToken,
                onValueChange = { wsToken = it },
                label = { Text("WebSocket token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { saveAndConnect(host, port, wsToken) }) {
                    Text("Connect")
                }

                OutlinedButton(onClick = { ConnectionService.stop(context) }) {
                    Text("Disconnect")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Reconnect when the phone boots",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = reconnectOnBoot,
                    onCheckedChange = { enabled ->
                        reconnectOnBoot = enabled
                        ConnectionPrefs.setReconnectOnBoot(context.applicationContext, enabled)
                    },
                )
            }
            OutlinedButton(onClick = {
                ConnectionService.stop(context)
                ConnectionPrefs.clear(context.applicationContext)
                ServerPrefs.forget(context.applicationContext)
                reloadServers()
                host = ConnectionPrefs.DEFAULT_HOST
                port = ConnectionPrefs.DEFAULT_PORT.toString()
                wsToken = ""
                reconnectOnBoot = true
            }) {
                Text("Forget saved host")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Stop all sharing", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "On only when location, presence, screen time, and health are all paused. One tap turns sharing back on.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = sharingStopped,
                    onCheckedChange = { on ->
                        val app = context.applicationContext
                        if (on) SharingMaster.stopAll(app) else SharingMaster.resumeAll(app)
                        sharingStopped = SharingMaster.allPaused(app)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("PhonePi quiet 1 hour", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Holds PhonePi's own non-urgent notifications. Does not change system Do Not Disturb.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = quietOn,
                    onCheckedChange = { on ->
                        val app = context.applicationContext
                        if (on) {
                            PhonePiQuiet.quietFor(app, 60)
                            quietOn = true
                        } else {
                            PhonePiQuiet.restore(app)
                            quietOn = false
                        }
                    },
                )
            }
        }

            HorizontalDivider()
            UpdatePanel()
            HorizontalDivider()
            PermissionsPanel()
            HorizontalDivider()
            LocationHistoryPanel()
            HorizontalDivider()
            PresencePanel()
            HorizontalDivider()
            UsagePanel()
            HorizontalDivider()
            HealthPanel()
            HorizontalDivider()
            PhotosPanel()
            HorizontalDivider()
            VolumePanel()
            HorizontalDivider()
            TextsPanel()
            HorizontalDivider()
            AskAssistantPanel()
            HorizontalDivider()
            NotificationSharePanel()
            HorizontalDivider()
            ActivityLogPanel()
        }
    }
}

