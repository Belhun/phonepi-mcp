package dev.phonepi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.usage.UsageAccess
import dev.phonepi.companion.usage.UsageEngine
import dev.phonepi.companion.usage.UsagePrefs
import kotlinx.coroutines.launch

@Composable
fun UsagePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                UsageEngine.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val enabled = remember(refreshKey) { UsagePrefs.enabled(context) }
    val paused = remember(refreshKey) { UsagePrefs.paused(context) }
    val granted = remember(refreshKey) { UsageAccess.isGranted(context) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Screen time", style = MaterialTheme.typography.titleMedium)
        Text(
            "On by default. Bots can read daily screen-on minutes, unlock count, top apps, " +
                "and last used. Totals only — no event timeline and no notification contents. " +
                "Android still needs Usage access (Settings → Usage access). " +
                "A daily copy is stored on this phone because usage events expire after a few days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Screen time sharing", modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    UsagePrefs.setEnabled(context, on)
                    if (!on) {
                        UsagePrefs.setPaused(context, false)
                        ActivityLog.logUsage("Screen time off", "info")
                    } else {
                        ActivityLog.logUsage("Screen time on", "success")
                        UsageEngine.refresh(context)
                    }
                    refreshKey++
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (granted) "Usage access — granted" else "Usage access",
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            if (!granted) {
                OutlinedButton(onClick = { context.startActivity(UsageAccess.settingsIntent()) }) {
                    Text("Grant")
                }
            }
        }
        if (enabled && !granted) {
            Text(
                "Usage access is off, so screen time stays empty until you grant it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (enabled) {
            OutlinedButton(onClick = {
                val next = !paused
                UsagePrefs.setPaused(context, next)
                if (!next) UsageEngine.refresh(context)
                ActivityLog.logUsage(
                    if (next) "Screen time paused" else "Screen time resumed",
                    "info",
                )
                refreshKey++
            }) {
                Text(if (paused) "Resume logging" else "Pause logging")
            }
        }
        OutlinedButton(onClick = { confirmDelete = true }) {
            Text("Delete all usage")
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete screen time history?") },
            text = { Text("This deletes daily totals on this phone and asks the box to delete its copy.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { UsageEngine.clear(context) }
                    refreshKey++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
