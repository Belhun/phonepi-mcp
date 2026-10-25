package dev.phonepi.companion.ui

import android.Manifest
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
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
import dev.phonepi.companion.data.PhonePiDatabase
import dev.phonepi.companion.data.SavedLocationEntity
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.presence.PresenceAccess
import dev.phonepi.companion.presence.PresenceCapture
import dev.phonepi.companion.presence.PresenceEngine
import dev.phonepi.companion.presence.PresencePrefs
import kotlinx.coroutines.launch

@Composable
fun PresencePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf<List<SavedLocationEntity>>(emptyList()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refreshKey) {
        places = PhonePiDatabase.get(context).savedLocationDao().getAll()
    }

    val enabled = remember(refreshKey) { PresencePrefs.enabled(context) }
    val paused = remember(refreshKey) { PresencePrefs.paused(context) }
    val fine = remember(refreshKey) { PresenceAccess.fineGranted(context) }
    val background = remember(refreshKey) { PresenceAccess.backgroundGranted(context) }
    val activity = remember(refreshKey) { PresenceAccess.activityGranted(context) }
    val marks = remember(refreshKey) { PresencePrefs.marks(context) }

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        PresenceEngine.nudge(context)
        refreshKey++
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !PresenceAccess.activityGranted(context)) {
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            PresenceEngine.nudge(context)
        }
        refreshKey++
    }
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            noteOff(context, "Presence stays off without location permission.")
            refreshKey++
            return@rememberLauncherForActivityResult
        }
        PresencePrefs.setEnabled(context, true)
        ActivityLog.logPresence("Presence on", "success")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !PresenceAccess.backgroundGranted(context)) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !PresenceAccess.activityGranted(context)) {
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            PresenceEngine.nudge(context)
        }
        refreshKey++
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Presence", style = MaterialTheme.typography.titleMedium)
        Text(
            "On by default. Bots can ask where you are (place label, minutes there, driving, Do Not Disturb, and coordinates you approved). " +
                "Mark saved places as presence to get home/work/church geofences. Up to 100. Grant location Allow all the time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Presence", modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (!on) {
                        PresencePrefs.setEnabled(context, false)
                        PresencePrefs.setPaused(context, false)
                        PresenceCapture.stop(context)
                        ActivityLog.logPresence("Presence off", "info")
                        refreshKey++
                        return@Switch
                    }
                    if (fine) {
                        PresencePrefs.setEnabled(context, true)
                        ActivityLog.logPresence("Presence on", "success")
                        PresenceEngine.nudge(context)
                        refreshKey++
                    } else {
                        foregroundLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
            )
        }
        Text(presencePermissionLine(fine, background, activity), style = MaterialTheme.typography.bodySmall)
        if (enabled && !background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Text(
                "Allow all the time is off, so presence stops when PhonePi is in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }) {
                Text("Allow all the time")
            }
        }
        if (enabled) {
            OutlinedButton(onClick = {
                val next = !paused
                PresencePrefs.setPaused(context, next)
                if (next) PresenceCapture.stop(context) else PresenceEngine.nudge(context)
                ActivityLog.logPresence(
                    if (next) "Presence paused" else "Presence resumed",
                    "info",
                )
                refreshKey++
            }) {
                Text(if (paused) "Resume presence" else "Pause presence")
            }
        }
        OutlinedButton(onClick = { confirmClear = true }) {
            Text("Clear presence snapshot")
        }
        Text("Saved places marked presence", style = MaterialTheme.typography.bodyMedium)
        if (places.isEmpty()) {
            Text(
                "No saved places yet. Add one with phone_add_location and set presence true, or save a place then toggle it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        places.forEach { place ->
            val marked = place.nameKey in marks
            val hasCoords = place.latitude != null && place.longitude != null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(place.name)
                    Text(
                        if (hasCoords) "Geofence ready" else "Needs coordinates for a geofence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = marked,
                    onCheckedChange = { on ->
                        PresencePrefs.setMarked(context, place.nameKey, on)
                        PresenceEngine.nudge(context)
                        refreshKey++
                    },
                )
            }
        }
        Text(
            "Urgent PhonePi messages use the PhonePi urgent messages channel. " +
                "This app does not change Do Not Disturb. To hear those while DND is on, " +
                "open system Settings → Sound → Do Not Disturb → App notifications and allow PhonePi urgent messages yourself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear presence snapshot?") },
            text = { Text("This deletes the current place, minutes-at-place, and the box copy. Saved places stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { PresenceEngine.clear(context) }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

private fun noteOff(context: android.content.Context, message: String) {
    PresencePrefs.setEnabled(context, false)
    PresenceCapture.stop(context)
    ActivityLog.logPresence(message, "info")
}

private fun presencePermissionLine(fine: Boolean, background: Boolean, activity: Boolean): String {
    val loc = if (fine) "Precise location granted" else "Location permission needed"
    val allTime = if (background) "Allow all the time granted" else "Allow all the time needed for background presence"
    val motion = if (activity) "Activity recognition granted" else "Activity recognition off"
    return "$loc. $allTime. $motion."
}
