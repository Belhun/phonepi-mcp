package dev.phonepi.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dev.phonepi.companion.photos.PhotoAccess

@Composable
fun PhotosPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var sharingOn by remember(refreshKey) { mutableStateOf(PhotoAccess.sharingEnabled(context)) }
    val osGranted = remember(refreshKey) { PhotoAccess.hasOsPermission(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted || PhotoAccess.hasOsPermission(context)) {
            PhotoAccess.setSharingEnabled(context, true)
        }
        refreshKey++
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Recent photos", style = MaterialTheme.typography.titleMedium)
        Text(
            "Bots can list recent camera and gallery photos and fetch one image at a time. " +
                "PhonePi never deletes or edits photos. Sharing stays off until you turn it on. " +
                "Image bytes need the shared token. PhonePi recompresses each photo before sending, " +
                "so GPS and other EXIF tags are not in the file it returns.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Share recent photos",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = sharingOn,
                onCheckedChange = { on ->
                    if (!on) {
                        PhotoAccess.setSharingEnabled(context, false)
                        sharingOn = false
                        return@Switch
                    }
                    if (PhotoAccess.hasOsPermission(context)) {
                        PhotoAccess.setSharingEnabled(context, true)
                        sharingOn = true
                    } else {
                        launcher.launch(PhotoAccess.permissionToRequest())
                    }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (osGranted) "Photos permission: granted" else "Photos permission needed",
                style = MaterialTheme.typography.bodyMedium,
                color = if (osGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            if (!osGranted) {
                OutlinedButton(onClick = { launcher.launch(PhotoAccess.permissionToRequest()) }) {
                    Text("Grant")
                }
            }
        }
    }
}
