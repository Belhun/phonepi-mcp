package dev.phonepi.clock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phonepi.clock.data.AlarmEntity
import dev.phonepi.clock.schedule.DayMask

/**
 * Screen structure for the later design pass. Keep state and actions here
 * so docs/clock/DESIGN.md can replace layout without moving alarm behavior.
 */
object ClockThemeTokens {
    val scheme = darkColorScheme()
}

data class AlarmDraft(
    val hour: String,
    val minute: String,
    val label: String,
    val daysMask: Int,
    val vibrate: Boolean,
    val rampSeconds: String,
    val snoozeMinutes: String,
    val silenceAfterMinutes: String,
    val soundType: String,
    val soundName: String,
    val soundUri: String,
)

fun AlarmEntity.toDraft() = AlarmDraft(
    hour = hour.toString(),
    minute = minute.toString().padStart(2, '0'),
    label = label,
    daysMask = daysMask,
    vibrate = vibrate,
    rampSeconds = rampSeconds.toString(),
    snoozeMinutes = snoozeMinutes.toString(),
    silenceAfterMinutes = silenceAfterMinutes.toString(),
    soundType = soundType,
    soundName = soundName,
    soundUri = soundUri,
)

fun blankDraft() = AlarmDraft("7", "00", "", 0, true, "0", "10", "15", "default", "Default", "")

@Composable
fun ClockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClockThemeTokens.scheme, content = content)
}

@Composable
fun AlarmListScreen(
    header: String,
    health: String,
    alarms: List<AlarmEntity>,
    onAdd: () -> Unit,
    onOpen: (AlarmEntity) -> Unit,
    onToggle: (AlarmEntity, Boolean) -> Unit,
    onSkip: (AlarmEntity) -> Unit,
    onBattery: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PhonePi Clock", style = MaterialTheme.typography.headlineMedium)
        Text(header, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(health, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAdd) { Text("Add alarm") }
        TextButton(onClick = onBattery) { Text("Battery settings") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(alarms, key = { it.id }) { alarm ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(clockTime(alarm.hour, alarm.minute), fontSize = 32.sp)
                            Text(listOfNotNull(dayLabel(alarm.daysMask), alarm.label.ifBlank { null }).joinToString(" · "))
                            if (alarm.skipNext) Text("Next one skipped")
                        }
                        Switch(checked = alarm.enabled, onCheckedChange = { onToggle(alarm, it) })
                    }
                    Row {
                        TextButton(onClick = { onOpen(alarm) }) { Text("Edit") }
                        TextButton(onClick = { onSkip(alarm) }) { Text(if (alarm.skipNext) "Undo skip" else "Skip next") }
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmEditorScreen(
    draft: AlarmDraft,
    onChange: (AlarmDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onSound: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Alarm", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft.hour, { onChange(draft.copy(hour = it)) }, label = { Text("Hour") }, modifier = Modifier.weight(1f))
            OutlinedTextField(draft.minute, { onChange(draft.copy(minute = it)) }, label = { Text("Minute") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(draft.label, { onChange(draft.copy(label = it)) }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
        DayChips(draft.daysMask) { onChange(draft.copy(daysMask = it)) }
        Text("Sound: ${draft.soundName.ifBlank { draft.soundType }}")
        Row {
            TextButton(onClick = { onSound("default") }) { Text("Default") }
            TextButton(onClick = { onSound("silent") }) { Text("Silent") }
            TextButton(onClick = { onSound("ringtone") }) { Text("Ringtone") }
            TextButton(onClick = { onSound("file") }) { Text("File") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vibrate", Modifier.weight(1f))
            Switch(draft.vibrate, { onChange(draft.copy(vibrate = it)) })
        }
        OutlinedTextField(draft.rampSeconds, { onChange(draft.copy(rampSeconds = it)) }, label = { Text("Volume ramp seconds") })
        OutlinedTextField(draft.snoozeMinutes, { onChange(draft.copy(snoozeMinutes = it)) }, label = { Text("Snooze minutes") })
        OutlinedTextField(draft.silenceAfterMinutes, { onChange(draft.copy(silenceAfterMinutes = it)) }, label = { Text("Silence after minutes") })
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
fun RingScreen(time: String, label: String, onSnooze: () -> Unit, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(time, fontSize = 64.sp, fontWeight = FontWeight.Medium)
        Text(label, fontSize = 24.sp, modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))
        Button(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) { Text("Snooze") }
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Stop") }
    }
}

@Composable
private fun DayChips(mask: Int, onChange: (Int) -> Unit) {
    val days = listOf(
        "Mon" to DayMask.MONDAY,
        "Tue" to DayMask.TUESDAY,
        "Wed" to DayMask.WEDNESDAY,
        "Thu" to DayMask.THURSDAY,
        "Fri" to DayMask.FRIDAY,
        "Sat" to DayMask.SATURDAY,
        "Sun" to DayMask.SUNDAY,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { (name, bit) ->
            FilterChip(
                selected = mask and bit != 0,
                onClick = { onChange(if (mask and bit != 0) mask and bit.inv() else mask or bit) },
                label = { Text(name) },
            )
        }
    }
}

fun clockTime(hour: Int, minute: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h, minute, if (hour < 12) "AM" else "PM")
}

fun dayLabel(mask: Int): String = when (mask) {
    0 -> "Once"
    DayMask.WEEKDAYS -> "Weekdays"
    DayMask.WEEKENDS -> "Weekends"
    DayMask.ALL -> "Every day"
    else -> listOf(
        "Mon" to DayMask.MONDAY,
        "Tue" to DayMask.TUESDAY,
        "Wed" to DayMask.WEDNESDAY,
        "Thu" to DayMask.THURSDAY,
        "Fri" to DayMask.FRIDAY,
        "Sat" to DayMask.SATURDAY,
        "Sun" to DayMask.SUNDAY,
    ).filter { mask and it.second != 0 }.joinToString(" ") { it.first }
}
