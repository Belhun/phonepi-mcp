package dev.phonepi.companion.ui

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
import dev.phonepi.companion.volume.MidnightVolume
import dev.phonepi.companion.volume.VolumeController
import dev.phonepi.companion.volume.VolumeParse

@Composable
fun VolumePanel(modifier: Modifier = Modifier) {
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
    var midnightOn by remember(refreshKey) { mutableStateOf(MidnightVolume.enabled(context)) }
    val exactOk = remember(refreshKey) { MidnightVolume.exactAlarmsAllowed(context) }
    val policyOk = remember(refreshKey) { VolumeController.hasNotificationPolicyAccess(context) }
    val dndOn = remember(refreshKey) {
        VolumeParse.dndIsOn(VolumeController.interruptionFilter(context))
    }
    val needExact = midnightOn && !exactOk
    val needDndPolicy = (midnightOn || dndOn) && !policyOk

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Volumes", style = MaterialTheme.typography.titleMedium)
        Text(
            "Max volumes at midnight sets alarm, ring, notification, media, voice, and system " +
                "to max at local 00:00 even if the PC is offline. Exact alarms survive reboot and " +
                "app update. Do Not Disturb can ignore ring and notification volume unless PhonePi " +
                "has Do Not Disturb access; alarm volume still works.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Max volumes at midnight",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = midnightOn,
                onCheckedChange = { on ->
                    if (on && !MidnightVolume.exactAlarmsAllowed(context)) {
                        midnightOn = false
                        context.startActivity(MidnightVolume.exactAlarmSettingsIntent(context))
                        return@Switch
                    }
                    midnightOn = on
                    MidnightVolume.setEnabled(context, on)
                    refreshKey++
                },
            )
        }
        if (needExact) {
            GrantRow(
                label = "Alarms & reminders (exact midnight alarm)",
                onGrant = {
                    context.startActivity(MidnightVolume.exactAlarmSettingsIntent(context))
                },
            )
        }
        if (needDndPolicy) {
            GrantRow(
                label = "Do Not Disturb access (ring/notification while DND is on)",
                onGrant = {
                    context.startActivity(VolumeController.notificationPolicySettingsIntent())
                },
            )
        }
    }
}

@Composable
private fun GrantRow(label: String, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        OutlinedButton(onClick = onGrant) { Text("Grant") }
    }
}
