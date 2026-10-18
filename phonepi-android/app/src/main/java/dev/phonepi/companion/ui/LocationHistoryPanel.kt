package dev.phonepi.companion.ui

import android.Manifest
import android.net.Uri
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
import dev.phonepi.companion.history.LocationAccess
import dev.phonepi.companion.history.LocationCapture
import dev.phonepi.companion.history.LocationHistoryEngine
import dev.phonepi.companion.history.LocationHistoryPrefs
import dev.phonepi.companion.log.ActivityLog
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

object TimelineImportBus {
    private val generation = MutableStateFlow(0)
    private var pending: Uri? = null
    val updates = generation

    fun offer(uri: Uri) {
        pending = uri
        generation.value = generation.value + 1
    }

    fun consume(): Uri? {
        val uri = pending
        pending = null
        return uri
    }
}

@Composable
fun LocationHistoryPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val enabled = remember(refreshKey) { LocationHistoryPrefs.enabled(context) }
    val paused = remember(refreshKey) { LocationHistoryPrefs.paused(context) }
    val fine = remember(refreshKey) { LocationAccess.fineGranted(context) }
    val coarse = remember(refreshKey) { LocationAccess.coarseGranted(context) }
    val background = remember(refreshKey) { LocationAccess.backgroundGranted(context) }
    val activity = remember(refreshKey) { LocationAccess.activityGranted(context) }

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        LocationCapture.refresh(context)
        refreshKey++
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !LocationAccess.activityGranted(context)) {
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            LocationCapture.refresh(context)
        }
        refreshKey++
    }
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            LocationHistoryPrefs.setEnabled(context, false)
            LocationCapture.stop(context)
            note = "Location history stays off without location permission."
            refreshKey++
            return@rememberLauncherForActivityResult
        }
        LocationHistoryPrefs.setEnabled(context, true)
        ActivityLog.logLocation("Location history on", "success")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !LocationAccess.backgroundGranted(context)
        ) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !LocationAccess.activityGranted(context)
        ) {
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            LocationCapture.refresh(context)
        }
        refreshKey++
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) TimelineImportBus.offer(uri)
    }

    val importGeneration by TimelineImportBus.updates.collectAsState()
    LaunchedEffect(importGeneration) {
        if (importGeneration == 0) return@LaunchedEffect
        val uri = TimelineImportBus.consume() ?: return@LaunchedEffect
        val text = withContext(Dispatchers.IO) { readTimeline(context.contentResolver.openInputStream(uri)) }
        if (text == null) {
            note = "Could not read that file. Exports larger than 40 MB are skipped."
            ActivityLog.logLocation(note, "error")
            return@LaunchedEffect
        }
        val result = LocationHistoryEngine.importTimeline(context, text)
        note = result.error
            ?: "Imported ${result.visits} visits and ${result.drives} drives. ${result.skipped} skipped."
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Location history", style = MaterialTheme.typography.titleMedium)
        Text(
            "Off until you turn it on. PhonePi records visits and drives on this phone and syncs them to your box. " +
                "It uses balanced location, activity changes, a geofence, and significant motion. " +
                "It does not keep the GPS radio on. Addresses come from Android's Geocoder on this phone. " +
                "Nothing is sent to a third-party service.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Location history", modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (!on) {
                        LocationHistoryPrefs.setEnabled(context, false)
                        LocationHistoryPrefs.setPaused(context, false)
                        LocationCapture.stop(context)
                        ActivityLog.logLocation("Location history off", "info")
                        refreshKey++
                        return@Switch
                    }
                    if (fine || coarse) {
                        LocationHistoryPrefs.setEnabled(context, true)
                        ActivityLog.logLocation("Location history on", "success")
                        LocationCapture.refresh(context)
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
        Text(permissionLine(fine, coarse, background, activity), style = MaterialTheme.typography.bodySmall)
        if (enabled && !background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Text(
                "Allow all the time is off, so history stops when PhonePi is in the background.",
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
                LocationHistoryPrefs.setPaused(context, next)
                if (next) LocationCapture.stop(context) else LocationCapture.refresh(context)
                ActivityLog.logLocation(if (next) "Location logging paused" else "Location logging resumed", "info")
                refreshKey++
            }) {
                Text(if (paused) "Resume logging" else "Pause logging")
            }
        }
        OutlinedButton(onClick = { confirmDelete = true }) {
            Text("Delete all history")
        }
        OutlinedButton(onClick = {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }) {
            Text("Import Timeline export")
        }
        Text(
            "To backfill Google Timeline, export it yourself: Settings, Location, Location services, Timeline, Export Timeline data. Then share the JSON here or pick the file.",
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
            title = { Text("Delete location history?") },
            text = { Text("This deletes every visit and drive on this phone and asks the box to delete its copy.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { LocationHistoryEngine.clear(context) }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private fun permissionLine(fine: Boolean, coarse: Boolean, background: Boolean, activity: Boolean): String {
    val precise = when {
        fine -> "Precise location granted"
        coarse -> "Approximate location granted"
        else -> "Location permission needed"
    }
    val allTime = if (background) "Allow all the time granted" else "Allow all the time needed for background logging"
    val motion = if (activity) "Activity recognition granted" else "Activity recognition off"
    return "$precise. $allTime. $motion."
}

private fun readTimeline(input: InputStream?): String? {
    if (input == null) return null
    input.use { stream ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > 40 * 1024 * 1024) return null
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
