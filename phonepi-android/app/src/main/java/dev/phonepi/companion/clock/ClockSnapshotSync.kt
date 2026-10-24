package dev.phonepi.companion.clock

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Pushes the Clock snapshot to the box over the phone WebSocket.
 * Frame type is clock_snapshot. The box keeps the last copy for offline reads.
 */
object ClockSnapshotSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var sender: ((String) -> Boolean)? = null
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context, send: (String) -> Boolean) {
        appContext = context.applicationContext
        sender = send
        val app = appContext ?: return
        scope.launch {
            val payload = ClockBridge.call(app, "clock_list_alarms", "{}")
            val snapshot = ClockBridge.snapshotFrom(payload) ?: return@launch
            publish(snapshot.toString())
        }
    }

    fun detach() {
        sender = null
    }

    fun publish(snapshotJson: String) {
        val send = sender ?: return
        val snapshot = try {
            JSONObject(snapshotJson)
        } catch (_: Exception) {
            return
        }
        send(JSONObject().put("type", "clock_snapshot").put("snapshot", snapshot).toString())
    }
}
