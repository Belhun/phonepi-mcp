package dev.phonepi.companion.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.phonepi.companion.notify.NotificationAccess
import dev.phonepi.companion.permissions.PermissionTier
import dev.phonepi.companion.usage.UsageAccess
import dev.phonepi.companion.update.UnknownAppInstall
import dev.phonepi.companion.permissions.isGranted
import dev.phonepi.companion.permissions.missingPermissions

@Composable
fun PermissionsPanel(modifier: Modifier = Modifier) {
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
    val overlayOk = remember(refreshKey) { Settings.canDrawOverlays(context) }
    val notificationAccessOk = remember(refreshKey) { NotificationAccess.isGranted(context) }
    val usageAccessOk = remember(refreshKey) { UsageAccess.isGranted(context) }
    val unknownAppsOk = remember(refreshKey) { UnknownAppInstall.isGranted(context) }
    val fsiOk = remember(refreshKey) {
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else {
            true
        }
    }
    val tiers = remember(refreshKey) {
        PermissionTier.entries.map { tier ->
            tier to tier.isGranted(context)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshKey++ }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Tool permissions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Grant access before running call, contact, location, navigation, notification, or screen time tools. " +
                "Display over other apps is what lets Maps open while PhonePi is in the background. " +
                "Notification access is a separate system setting; PhonePi still shares no apps until you pick them. " +
                "Usage access is Settings → Usage access; it is not a runtime dialog.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermissionTierRow(
            label = "Install unknown apps",
            granted = unknownAppsOk,
            onGrant = {
                context.startActivity(UnknownAppInstall.settingsIntent(context))
            },
        )

        PermissionTierRow(
            label = "Notification access",
            granted = notificationAccessOk,
            onGrant = {
                context.startActivity(NotificationAccess.settingsIntent(context))
            },
        )

        PermissionTierRow(
            label = "Usage access",
            granted = usageAccessOk,
            onGrant = {
                context.startActivity(UsageAccess.settingsIntent())
            },
        )

        PermissionTierRow(
            label = "Display over other apps",
            granted = overlayOk,
            onGrant = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        )

        PermissionTierRow(
            label = "Maps navigation — full-screen intents",
            granted = fsiOk,
            onGrant = {
                val intent = if (Build.VERSION.SDK_INT >= 34) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                } else {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                }
                context.startActivity(intent)
            },
        )

        tiers.forEach { (tier, granted) ->
            PermissionTierRow(
                label = tier.label,
                granted = granted,
                onGrant = {
                    val missing = tier.missingPermissions(context)
                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                },
            )
        }
    }
}

@Composable
private fun PermissionTierRow(
    label: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (granted) "$label — granted" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (!granted) {
            OutlinedButton(onClick = onGrant) {
                Text("Grant")
            }
        }
    }
}
