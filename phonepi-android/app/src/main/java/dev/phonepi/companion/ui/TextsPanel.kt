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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.phonepi.companion.textsend.InVehicleMonitor
import dev.phonepi.companion.textsend.SendTextController
import dev.phonepi.companion.textsend.SendTextPrefs

@Composable
fun TextsPanel(modifier: Modifier = Modifier) {
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
    var allow by remember(refreshKey) { mutableStateOf(SendTextPrefs.allowRequests(context)) }
    var attributed by remember(refreshKey) { mutableStateOf(SendTextPrefs.allowAttributed(context)) }
    var driving by remember(refreshKey) { mutableStateOf(SendTextPrefs.drivingMode(context)) }
    val needed = remember(refreshKey) { missingTextPermissions(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val sms = result[Manifest.permission.SEND_SMS] == true || granted(context, Manifest.permission.SEND_SMS)
        val notes = if (Build.VERSION.SDK_INT >= 33) {
            result[Manifest.permission.POST_NOTIFICATIONS] == true ||
                granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        if (sms && notes) {
            SendTextPrefs.setAllowRequests(context, true)
            InVehicleMonitor.start(context)
        }
        refreshKey++
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Texts", style = MaterialTheme.typography.titleMedium)
        Text(
            "Bots can ask PhonePi to text one person from this phone. " +
                "Nothing sends until you turn this on. A text that sounds like you always needs Send, " +
                "or the word send while Driving mode, Android Auto, a car Bluetooth kit, or a detected drive is on. " +
                "Assistant-signed texts add \"[Alex's assistant Grok]: \" and can send without that tap only when the second switch is on. " +
                "Both switches start off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow("Allow bots to request texts", allow) { on ->
            if (!on) {
                SendTextPrefs.setAllowRequests(context, false)
                SendTextController.rejectOutstanding(context)
                allow = false
                return@ToggleRow
            }
            val ask = missingTextPermissions(context)
            if (ask.isEmpty()) {
                SendTextPrefs.setAllowRequests(context, true)
                InVehicleMonitor.start(context)
                allow = true
            } else {
                launcher.launch(ask.toTypedArray())
            }
        }
        ToggleRow("Allow assistant-signed texts without confirm", attributed) { on ->
            SendTextPrefs.setAllowAttributed(context, on)
            attributed = on
        }
        ToggleRow("Driving mode", driving) { on ->
            SendTextPrefs.setDrivingMode(context, on)
            driving = on
        }
        Text(
            "Driving mode reads the text aloud and listens for send or cancel. " +
                "Cancel, silence, or anything that is not a clear send leaves the notification so you can tap it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (needed.isNotEmpty()) {
            Text(
                "Still needed: ${needed.joinToString { it.substringAfterLast('.') }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { launcher.launch(needed.toTypedArray()) }) {
                Text("Grant")
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun missingTextPermissions(context: android.content.Context): List<String> {
    val names = mutableListOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACTIVITY_RECOGNITION,
    )
    if (Build.VERSION.SDK_INT >= 33) names += Manifest.permission.POST_NOTIFICATIONS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) names += Manifest.permission.BLUETOOTH_CONNECT
    return names.filter { !granted(context, it) }
}

private fun granted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
