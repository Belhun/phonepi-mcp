package dev.phonepi.companion.health

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Pushes a daily-totals health summary over the phone WebSocket.
 * A clear is sent before any new summary so a wipe cannot be undone by an old snapshot.
 */
object HealthSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var sender: ((String) -> Boolean)? = null
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context, send: (String) -> Boolean) {
        appContext = context.applicationContext
        sender = send
        scope.launch {
            if (HealthConnectPrefs.canSync(context)) run(context) else flush(context)
        }
    }

    fun detach() {
        sender = null
    }

    fun nudge() {
        val context = appContext ?: return
        scope.launch { flush(context) }
    }

    fun requestClear(context: Context) {
        appContext = context.applicationContext
        HealthConnectPrefs.setPendingRemoteClear(context, true)
        File(context.applicationContext.filesDir, "health-summary.json").delete()
        nudge()
    }

    fun onFrame(text: String) {
        val context = appContext ?: return
        val type = runCatching {
            json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (type == "health_cleared_ack") {
            HealthConnectPrefs.setPendingRemoteClear(context, false)
            nudge()
            return
        }
        if (type == "health_sync_ack") {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val ok = obj?.get("ok")?.jsonPrimitive?.contentOrNull != "false"
            if (!ok) {
                HealthConnectPrefs.setLastError(context, "box rejected health sync")
            }
        }
    }

    suspend fun run(context: Context) {
        val app = context.applicationContext
        if (!HealthConnectPrefs.canSync(app)) return
        val summary = try {
            HealthConnectReader.read(app)
        } catch (e: SecurityException) {
            HealthConnectPrefs.setLastError(app, "Health Connect permission denied")
            ActivityLog.logHealth("Health Connect permission denied", "error")
            return
        } catch (e: Exception) {
            HealthConnectPrefs.setLastError(app, e.message ?: "Health Connect read failed")
            ActivityLog.logHealth("Health Connect read failed", "error")
            return
        }
        if (!HealthPermissions.coreReadsGranted(summary.permissions)) {
            HealthConnectPrefs.setLastError(app, "Health Connect reads not granted")
            return
        }
        writeLocal(app, summary)
        HealthConnectPrefs.setLastSyncedMs(app, summary.syncedAtMs)
        HealthConnectPrefs.setLastError(app, "")
        ActivityLog.logHealth("Synced health summary", "success")
        appContext = app
        flush(app)
    }

    private suspend fun flush(context: Context) {
        mutex.withLock {
            val send = sender ?: return
            if (HealthConnectPrefs.pendingRemoteClear(context)) {
                if (!send("""{"type":"health_cleared"}""")) return
                return
            }
            if (!HealthConnectPrefs.canSync(context)) return
            val payload = readLocal(context) ?: return
            send(healthSyncMessage(payload))
        }
    }

    private fun writeLocal(context: Context, summary: HealthSummary) {
        File(context.filesDir, "health-summary.json").writeText(healthSummaryJson(summary))
    }

    private fun readLocal(context: Context): JsonObject? {
        val file = File(context.filesDir, "health-summary.json")
        if (!file.isFile) return null
        return runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
    }

    private fun healthSyncMessage(summary: JsonObject): String =
        buildJsonObject {
            put("type", "health_sync")
            put("summary", summary)
        }.toString()
}
