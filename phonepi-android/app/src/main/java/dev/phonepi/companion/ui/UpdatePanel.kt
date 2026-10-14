package dev.phonepi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phonepi.companion.connection.ConnectionController
import dev.phonepi.companion.connection.ConnectionState
import dev.phonepi.companion.update.UpdateController

@Composable
fun UpdatePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connection by ConnectionController.state.collectAsState()
    val update by UpdateController.state.collectAsState()

    LaunchedEffect(connection) {
        if (connection == ConnectionState.Connected) {
            UpdateController.quietCheck(context.applicationContext)
        }
    }

    val busy = update.checking || update.downloading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("App update", style = MaterialTheme.typography.titleMedium)
        if (update.banner != null) {
            Text(
                update.banner!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "Downloads the APK from your PC, checks sha256 and the signing cert, then opens the system installer. " +
                "You still tap Install. Grant Install unknown apps once below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { UpdateController.checkAndInstall(context.applicationContext, force = false) },
                enabled = !busy,
            ) {
                Text("Check for update")
            }
            OutlinedButton(
                onClick = { UpdateController.checkAndInstall(context.applicationContext, force = true) },
                enabled = !busy,
            ) {
                Text("Force update")
            }
        }
        if (update.downloading) {
            LinearProgressIndicator(
                progress = { update.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        update.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        update.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
