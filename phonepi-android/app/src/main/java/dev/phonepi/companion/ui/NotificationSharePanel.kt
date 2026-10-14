package dev.phonepi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.phonepi.companion.notify.NotificationAccess
import dev.phonepi.companion.notify.NotificationShare

@Composable
fun NotificationSharePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val share by NotificationShare.state.collectAsState()
    var refreshKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessGranted = remember(refreshKey) { NotificationAccess.isGranted(context) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Notification sharing", style = MaterialTheme.typography.titleMedium)
        Text(
            if (!accessGranted) {
                "Grant Notification access above first. PhonePi only lists apps that have posted a notification. " +
                    "Nothing is shared with the PC until you pick apps here. PhonePi never dismisses or replies."
            } else {
                "Apps appear after they post a notification. Off by default: pick each app, or turn on Share all. " +
                    "PhonePi never dismisses, replies to, or acts on notifications."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Share all notification apps",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = share.shareAll,
                onCheckedChange = { NotificationShare.setShareAll(it) },
                enabled = accessGranted,
            )
        }

        if (share.seenApps.isEmpty()) {
            Text(
                "No apps yet. After Notification access is on, the next apps that notify will show up here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                share.seenApps.forEach { app ->
                    val allowed = share.shareAll || app.packageName in share.allowedPackages
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = allowed,
                            onCheckedChange = { on ->
                                NotificationShare.setAllowed(app.packageName, on)
                            },
                            enabled = accessGranted && !share.shareAll,
                        )
                    }
                }
            }
        }
    }
}
