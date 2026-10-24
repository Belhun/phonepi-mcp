package dev.phonepi.clock.ui

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.phonepi.clock.ClockEngine
import dev.phonepi.clock.data.AlarmEntity
import dev.phonepi.clock.schedule.DayMask
import dev.phonepi.clock.schedule.nextRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var alarms by mutableStateOf<List<AlarmEntity>>(emptyList())
    private var editing by mutableStateOf<String?>(null)
    private var creating by mutableStateOf(false)
    private var draft by mutableStateOf(blankDraft())
    private var health by mutableStateOf("Checking the schedule")

    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri == null) {
            draft = draft.copy(soundType = "silent", soundName = "Silent", soundUri = "")
        } else {
            draft = draft.copy(soundType = "ringtone", soundUri = uri.toString(), soundName = "Ringtone")
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val copied = copySound(uri)
        draft = draft.copy(soundType = "file", soundUri = copied, soundName = displayName(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteSpotifyCallback(intent)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            ClockTheme {
                if (creating || editing != null) {
                    AlarmEditorScreen(
                        draft = draft,
                        onChange = { draft = it },
                        onSave = { saveDraft() },
                        onDelete = editing?.let { id -> { delete(id) } },
                        onSound = { chooseSound(it) },
                        onBack = { creating = false; editing = null },
                    )
                } else {
                    AlarmListScreen(
                        header = nextHeader(),
                        health = health,
                        alarms = alarms,
                        onAdd = { draft = blankDraft(); creating = true },
                        onOpen = { alarm -> draft = alarm.toDraft(); editing = alarm.id },
                        onToggle = { alarm, enabled -> toggle(alarm, enabled) },
                        onSkip = { alarm -> skip(alarm) },
                        onBattery = { openBatterySettings() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteSpotifyCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun noteSpotifyCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "phonepi-clock" && data.host == "spotify-callback") {
            // Phase 3 handles Spotify. The scheme is registered so a developer app can be created.
        }
    }

    private fun reload() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { ClockEngine.list(applicationContext) }
            alarms = rows
            health = withContext(Dispatchers.IO) { healthLine() }
        }
    }

    private fun healthLine(): String {
        val raw = ClockEngine.handle(applicationContext, "clock_health", "{}")
        val json = JSONObject(raw).optJSONObject("health") ?: return "Health unknown"
        val status = json.optString("status")
        val detail = json.optString("detail")
        return if (detail.isBlank()) "Schedule $status" else "Schedule $status. $detail"
    }

    private fun nextHeader(): String {
        val next = alarms.filter { it.enabled }.mapNotNull { alarm ->
            nextRing(alarm.hour, alarm.minute, alarm.daysMask, alarm.skipNext, ZonedDateTime.now())
        }.minByOrNull { it.toInstant() }
        return if (next == null) "No alarm set" else "Next " + next.format(DateTimeFormatter.ofPattern("EEE h:mm a"))
    }

    private fun saveDraft() {
        val hour = draft.hour.toIntOrNull()
        val minute = draft.minute.toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) return
        val fields = JSONObject().apply {
            put("hour", hour)
            put("minute", minute)
            put("label", draft.label)
            put("days", draft.daysMask)
            put("vibrate", draft.vibrate)
            put("rampSeconds", draft.rampSeconds.toIntOrNull() ?: 0)
            put("snoozeMinutes", draft.snoozeMinutes.toIntOrNull() ?: 10)
            put("silenceAfterMinutes", draft.silenceAfterMinutes.toIntOrNull() ?: 15)
            put("soundType", draft.soundType)
            put("soundUri", draft.soundUri)
            put("soundName", draft.soundName)
            put("createdBy", "app")
        }
        val id = editing
        lifecycleScope.launch(Dispatchers.IO) {
            ClockEngine.saveFromUi(applicationContext, id, fields, "app")
            withContext(Dispatchers.Main) {
                creating = false
                editing = null
                reload()
            }
        }
    }

    private fun toggle(alarm: AlarmEntity, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            ClockEngine.setEnabledFromUi(applicationContext, alarm.id, enabled)
            withContext(Dispatchers.Main) { reload() }
        }
    }

    private fun skip(alarm: AlarmEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (alarm.skipNext) {
                ClockEngine.handle(applicationContext, "clock_unskip", JSONObject().put("id", alarm.id).put("requestId", UUID.randomUUID().toString()).toString())
            } else {
                ClockEngine.skipFromUi(applicationContext, alarm.id)
            }
            withContext(Dispatchers.Main) { reload() }
        }
    }

    private fun delete(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            ClockEngine.deleteFromUi(applicationContext, id)
            withContext(Dispatchers.Main) {
                editing = null
                reload()
            }
        }
    }

    private fun chooseSound(kind: String) {
        when (kind) {
            "silent" -> draft = draft.copy(soundType = "silent", soundName = "Silent", soundUri = "")
            "default" -> draft = draft.copy(soundType = "default", soundName = "Default", soundUri = "")
            "ringtone" -> {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                }
                ringtonePicker.launch(intent)
            }
            "file" -> filePicker.launch(arrayOf("audio/*"))
        }
    }

    private fun copySound(uri: Uri): String {
        val dir = File(createDeviceProtectedStorageContext().filesDir, "sounds")
        dir.mkdirs()
        val dest = File(dir, UUID.randomUUID().toString())
        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest.absolutePath
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "Sound"
        }
        return "Sound"
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
