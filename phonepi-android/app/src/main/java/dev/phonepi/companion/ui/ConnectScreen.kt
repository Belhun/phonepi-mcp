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
import dev.phonepi.companion.connection.ConnectionController
import dev.phonepi.companion.connection.ConnectionPrefs
import dev.phonepi.companion.connection.ConnectionService
import dev.phonepi.companion.connection.ConnectionState
import dev.phonepi.companion.connection.SetupBus

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

    fun connectNow(nextHost: String, nextPort: String, nextToken: String = "") {
        ensureNotifThen {
            ConnectionService.start(
                context,
                nextHost.trim(),
                nextPort.toIntOrNull() ?: ConnectionPrefs.DEFAULT_PORT,
                nextToken,
            )
        }
    }

    LaunchedEffect(setup) {
        val target = setup ?: return@LaunchedEffect
        host = target.host
        port = target.port.toString()
        connectNow(target.host, target.port.toString(), target.wsToken)
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
            Text("Status: ${state.label()}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Scan the Odysseus Settings → Phone QR, or paste the Tailscale host. " +
                    "Serve uses port 443. Disconnect keeps the saved host; Forget saved host wipes it.",
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
                Button(onClick = { connectNow(host, port, wsToken) }) {
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
                host = ConnectionPrefs.DEFAULT_HOST
                port = ConnectionPrefs.DEFAULT_PORT.toString()
                wsToken = ""
                reconnectOnBoot = true
            }) {
                Text("Forget saved host")
            }
        }

            HorizontalDivider()
            UpdatePanel()
            HorizontalDivider()
            PermissionsPanel()
            HorizontalDivider()
            NotificationSharePanel()
            HorizontalDivider()
            ActivityLogPanel()
        }
    }
}

private fun ConnectionState.label(): String = when (this) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting\u2026"
    ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Reconnecting -> "Reconnecting (#$attempt)"
    is ConnectionState.Failed -> "Failed: $reason"
}
