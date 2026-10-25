package dev.phonepi.companion.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.phonepi.companion.health.HealthConnectPrefs
import dev.phonepi.companion.health.HealthPermissionFlags
import dev.phonepi.companion.health.HealthPermissions
import dev.phonepi.companion.health.HealthSync
import dev.phonepi.companion.health.HealthSyncScheduler
import dev.phonepi.companion.health.SOURCE_LAG_NOTE
import dev.phonepi.companion.health.grantStateLine
import dev.phonepi.companion.log.ActivityLog
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HealthPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var flags by remember { mutableStateOf(HealthPermissionFlags.NONE) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val enabled = remember(refreshKey) { HealthConnectPrefs.enabled(context) }
    val paused = remember(refreshKey) { HealthConnectPrefs.paused(context) }
    val lastSynced = remember(refreshKey) { HealthConnectPrefs.lastSyncedMs(context) }
    val lastError = remember(refreshKey) { HealthConnectPrefs.lastError(context) }
    val sdkStatus = remember(refreshKey) { HealthPermissions.sdkStatus(context) }
    val sdkAvailable = sdkStatus == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE

    androidx.compose.runtime.LaunchedEffect(refreshKey) {
        flags = HealthPermissions.grantedFlags(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        HealthConnectPrefs.setRationaleShown(context, true)
        scope.launch {
            flags = HealthPermissions.grantedFlags(context)
            if (HealthPermissions.coreReadsGranted(flags)) {
                HealthSyncScheduler.ensure(context)
                HealthSync.run(context)
                ActivityLog.logHealth("Health Connect permissions granted", "success")
                note = "Health Connect permissions updated."
            } else {
                note = "Grant Sleep, Steps, Heart rate, Exercise, and Blood oxygen in Health Connect."
            }
            refreshKey++
        }
    }

    val privacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            permissionLauncher.launch(HealthPermissions.READ)
        }
    }

    fun requestHealthPermissions() {
        if (!sdkAvailable) {
            note = HealthConnectClientStatus.message(sdkStatus)
            return
        }
        if (!HealthConnectPrefs.rationaleShown(context)) {
            privacyLauncher.launch(
                Intent(context, HealthPrivacyActivity::class.java)
                    .putExtra(HealthPrivacyActivity.EXTRA_CONTINUE_TO_GRANT, true),
            )
            return
        }
        permissionLauncher.launch(HealthPermissions.READ)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sleep and health", style = MaterialTheme.typography.titleMedium)
        Text(
            "On by default. PhonePi reads Health Connect on this phone (Galaxy Watch 7 data via Samsung Health) " +
                "and syncs daily totals to your box once an hour. Bots get last night's sleep, steps, heart rate, " +
                "and workouts. Raw heart-rate streams stay off the box.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sleep and health", modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    HealthConnectPrefs.setEnabled(context, on)
                    if (!on) {
                        HealthConnectPrefs.setPaused(context, false)
                        HealthSyncScheduler.cancel(context)
                        ActivityLog.logHealth("Sleep and health off", "info")
                    } else {
                        HealthSyncScheduler.ensure(context)
                        ActivityLog.logHealth("Sleep and health on", "info")
                        if (!HealthPermissions.coreReadsGranted(flags)) {
                            requestHealthPermissions()
                        } else {
                            scope.launch { HealthSync.run(context) }
                        }
                    }
                    refreshKey++
                },
            )
        }
        Text(grantStateLine(flags, sdkAvailable), style = MaterialTheme.typography.bodySmall)
        Text(lastSyncedLine(lastSynced, lastError), style = MaterialTheme.typography.bodySmall)
        Text(
            SOURCE_LAG_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { requestHealthPermissions() }) {
            Text("Grant Health Connect")
        }
        OutlinedButton(onClick = {
            privacyLauncher.launch(
                Intent(context, HealthPrivacyActivity::class.java)
                    .putExtra(HealthPrivacyActivity.EXTRA_CONTINUE_TO_GRANT, false),
            )
        }) {
            Text("Privacy policy")
        }
        if (enabled) {
            OutlinedButton(onClick = {
                val next = !paused
                HealthConnectPrefs.setPaused(context, next)
                if (next) {
                    ActivityLog.logHealth("Health sync paused", "info")
                } else {
                    HealthSyncScheduler.ensure(context)
                    scope.launch { HealthSync.run(context) }
                    ActivityLog.logHealth("Health sync resumed", "info")
                }
                refreshKey++
            }) {
                Text(if (paused) "Resume sync" else "Pause sync")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    HealthSync.run(context)
                    refreshKey++
                    note = "Read Health Connect. $SOURCE_LAG_NOTE"
                }
            }) {
                Text("Sync now")
            }
        }
        OutlinedButton(onClick = { confirmDelete = true }) {
            Text("Delete health cache")
        }
        Text(
            "Samsung Health setup (Pixel, Galaxy Watch 7):\n" +
                "1. Open Samsung Health.\n" +
                "2. Tap the drawer or your profile, then Settings.\n" +
                "3. Tap Health Connect. Turn it on if asked.\n" +
                "4. Allow Samsung Health to write Sleep, Steps, Heart rate, Exercise, and Blood oxygen.\n" +
                "5. Back in PhonePi, tap Grant Health Connect and allow those same reads, plus background access. Past data is optional.\n" +
                "Health Connect is also under Android Settings > Health & fitness > Health Connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note.isNotEmpty()) {
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete health cache?") },
            text = { Text("This deletes the daily totals on this phone and asks the box to delete ~/.phonepi/health.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    HealthSync.requestClear(context)
                    refreshKey++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private fun lastSyncedLine(lastSyncedMs: Long, lastError: String): String {
    if (lastSyncedMs <= 0L) {
        return if (lastError.isNotEmpty()) "Not synced yet. $lastError" else "Not synced yet."
    }
    val whenText = DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(lastSyncedMs))
    return if (lastError.isNotEmpty()) "Last synced $whenText. $lastError" else "Last synced $whenText."
}

object HealthConnectClientStatus {
    fun message(status: Int): String = when (status) {
        androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            "Install or update Health Connect from Play Store, then return here."
        else -> "Health Connect is not available on this phone."
    }
}
