package dev.phonepi.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.phonepi.companion.connection.ConnectionController
import dev.phonepi.companion.connection.ConnectionPrefs
import dev.phonepi.companion.connection.ConnectionService
import dev.phonepi.companion.connection.ConnectionState

@Composable
fun ConnectScreen() {
    val context = LocalContext.current
    val state by ConnectionController.state.collectAsState()

    val saved = remember { ConnectionPrefs.getLastSuccessful(context.applicationContext) }
    var host by remember {
        mutableStateOf(saved?.host ?: ConnectionPrefs.DEFAULT_HOST)
    }
    var port by remember {
        mutableStateOf((saved?.port ?: ConnectionPrefs.DEFAULT_PORT).toString())
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The link works without it; only the status notification is affected. */ }

    Column(
        modifier = Modifier.fillMaxSize(),
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

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    ConnectionService.start(
                        context,
                        host.trim(),
                        port.toIntOrNull() ?: ConnectionPrefs.DEFAULT_PORT,
                    )
                }) {
                    Text("Connect")
                }

                OutlinedButton(onClick = { ConnectionService.stop(context) }) {
                    Text("Disconnect")
                }
            }
        }

        HorizontalDivider()
        PermissionsPanel()
        HorizontalDivider()
        ActivityLogPanel(modifier = Modifier.weight(1f))
    }
}

private fun ConnectionState.label(): String = when (this) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting\u2026"
    ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Reconnecting -> "Reconnecting (#$attempt)"
    is ConnectionState.Failed -> "Failed: $reason"
}
