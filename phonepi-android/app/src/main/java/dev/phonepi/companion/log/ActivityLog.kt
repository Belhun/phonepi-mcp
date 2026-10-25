package dev.phonepi.companion.log

import android.content.Context
import dev.phonepi.companion.data.ActivityLogEntity
import dev.phonepi.companion.data.PhonePiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ActivityLog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: dev.phonepi.companion.data.ActivityLogDao

    fun init(context: Context) {
        dao = PhonePiDatabase.get(context).activityLogDao()
    }

    fun observeRecent(limit: Int = 100): Flow<List<ActivityLogEntity>> =
        dao.observeRecent(limit)

    fun logMessage(detail: String, status: String = "info") {
        write(category = "message", title = "Interactive message", detail = detail, status = status)
    }

    fun logTimerHandoff(detail: String, status: String = "info") {
        write(category = "timer", title = "Timer handoff", detail = detail, status = status)
    }

    fun logConnection(title: String, detail: String, status: String = "info") {
        write(category = "connection", title = title, detail = detail, status = status)
    }

    fun logTool(tool: String, requestId: String, data: JsonObject) {
        val status = data["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val detail = when (status) {
            "success" -> summarizeSuccess(tool, data)
            "error" -> data["error"]?.jsonPrimitive?.contentOrNull ?: "Tool failed"
            else -> data.toString().take(240)
        }
        write(
            category = "tool",
            title = tool,
            detail = "[$requestId] $detail",
            status = status,
        )
    }

    fun clear() {
        scope.launch { dao.clearAll() }
    }

    private fun write(category: String, title: String, detail: String, status: String) {
        if (!::dao.isInitialized) return
        scope.launch {
            dao.insert(
                ActivityLogEntity(
                    timestamp = System.currentTimeMillis(),
                    category = category,
                    title = title,
                    detail = detail,
                    status = status,
                ),
            )
        }
    }

    private fun summarizeSuccess(tool: String, data: JsonObject): String = when (tool) {
        "get_battery_level" -> {
            val level = data["level"]?.jsonPrimitive?.contentOrNull ?: "?"
            val charging = data["isCharging"]?.jsonPrimitive?.contentOrNull ?: "?"
            "Battery $level% (charging=$charging)"
        }
        "copy_to_clipboard" -> "Copied ${data["length"]?.jsonPrimitive?.contentOrNull ?: "?"} chars"
        "set_timer" -> {
            val seconds = data["seconds"]?.jsonPrimitive?.contentOrNull ?: "?"
            val started = data["started"]?.jsonPrimitive?.contentOrNull ?: "?"
            val label = data["label"]?.jsonPrimitive?.contentOrNull
            val suffix = if (label != null) " $label" else ""
            "Timer ${seconds}s$suffix started=$started"
        }
        "set_alarm" -> {
            val hour = data["hour"]?.jsonPrimitive?.contentOrNull ?: "?"
            val minute = data["minute"]?.jsonPrimitive?.contentOrNull ?: "?"
            val label = data["label"]?.jsonPrimitive?.contentOrNull
            val started = data["started"]?.jsonPrimitive?.contentOrNull ?: "?"
            val suffix = if (label != null) " $label" else ""
            "Alarm $hour:$minute$suffix started=$started"
        }
        "dismiss_alarm" -> {
            val search = data["search"]?.jsonPrimitive?.contentOrNull ?: "?"
            val started = data["started"]?.jsonPrimitive?.contentOrNull ?: "?"
            "Dismiss alarm by $search started=$started"
        }
        "dismiss_timer" -> {
            val started = data["started"]?.jsonPrimitive?.contentOrNull ?: "?"
            "Dismiss timer started=$started"
        }
        "list_alarms" -> {
            val next = data["nextAlarm"]
            val hasNext = next != null && next !is kotlinx.serialization.json.JsonNull
            val count = (data["phonePiAlarms"] as? kotlinx.serialization.json.JsonArray)?.size
            "next=$hasNext phonePi=${count ?: "?"}"
        }
        "send_notification" -> "Posted notification"
        "find_phone" -> {
            val maps = data["mapsUrl"]?.jsonPrimitive?.contentOrNull
            val suffix = if (maps != null) " @ $maps" else ""
            "Alarm session ${data["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""}$suffix"
        }
        "stop_alarm" -> "Stopped ${data["sessionId"]?.jsonPrimitive?.contentOrNull ?: "alarm"}"
        "get_location" -> {
            val lat = data["latitude"]?.jsonPrimitive?.contentOrNull ?: "?"
            val lng = data["longitude"]?.jsonPrimitive?.contentOrNull ?: "?"
            "$lat, $lng"
        }
        "send_message" -> "Message ${data["messageId"]?.jsonPrimitive?.contentOrNull ?: ""}"
        "get_message_response" -> "State: ${data["state"]?.jsonPrimitive?.contentOrNull ?: "?"}"
        "add_snippet", "update_snippet" ->
            data["snippet"]?.toString()?.take(120) ?: "Snippet saved"
        "delete_snippet" -> "Deleted snippet id=${data["id"]?.jsonPrimitive?.contentOrNull}"
        "get_all_snippets", "search_snippets", "get_snippets_by_type" ->
            "Count: ${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"}"
        "share_snippet" ->
            "Shared via ${data["method"]?.jsonPrimitive?.contentOrNull ?: "default"} (may open share sheet)"
        "send_sms" -> {
            val sim = data["simName"]?.jsonPrimitive?.contentOrNull
                ?: data["simSlot"]?.jsonPrimitive?.contentOrNull?.let { "slot $it" }
            val suffix = if (sim != null) " via $sim" else ""
            "SMS to ${data["to"]?.jsonPrimitive?.contentOrNull ?: "?"}$suffix"
        }
        "list_sms_subscriptions" -> "${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"} SIM(s)"
        "get_sms_messages" -> {
            val who = data["contactName"]?.jsonPrimitive?.contentOrNull
                ?: data["phoneNumbers"]?.toString()?.take(40)
                ?: "?"
            "${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"} SMS with $who"
        }
        "make_call" -> "Call to ${data["to"]?.jsonPrimitive?.contentOrNull ?: "?"}"
        "get_recent_calls" -> "${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"} recent calls"
        "get_notifications" -> {
            val count = data["count"]?.jsonPrimitive?.contentOrNull ?: "?"
            val omitted = data["omittedUnsharedCount"]?.jsonPrimitive?.contentOrNull ?: "0"
            "$count notification(s) (omitted unshared=$omitted)"
        }
        "list_notification_apps" -> "${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"} notification app(s)"
        "get_contacts" -> "Listed ${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"} contacts"
        "search_contacts" -> "Found ${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"} contacts"
        "add_contact", "update_contact", "get_contact_by_id" -> "Contact updated"
        "delete_contact" -> "Deleted contact"
        "phone_list_locations" -> "${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"} saved locations"
        "phone_add_location" -> "Saved ${data["location"]?.toString()?.take(80) ?: "location"}"
        "phone_remove_location" -> "Removed ${data["removed"]?.jsonPrimitive?.contentOrNull ?: "location"}"
        "phone_navigate" -> {
            val query = data["display"]?.jsonPrimitive?.contentOrNull
                ?: data["query"]?.jsonPrimitive?.contentOrNull
                ?: "?"
            val method = data["method"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            "Navigate $query via $method"
        }
        "phone_push_update" -> {
            val shown = data["promptShown"]?.jsonPrimitive?.contentOrNull ?: "?"
            val method = data["method"]?.jsonPrimitive?.contentOrNull ?: "none"
            "Update promptShown=$shown via $method"
        }
        "get_recent_photos" ->
            "${data["count"]?.jsonPrimitive?.contentOrNull ?: "?"} photo(s)"
        "get_photo" -> {
            val id = data["id"]?.jsonPrimitive?.contentOrNull ?: "?"
            val name = data["displayName"]?.jsonPrimitive?.contentOrNull ?: ""
            val bytes = (data["encoding"] as? JsonObject)
                ?.get("byteLength")
                ?.jsonPrimitive
                ?.contentOrNull
            "Photo $id $name (${bytes ?: "?"} bytes)"
        }
        "get_volumes" ->
            "ringer=${data["ringerMode"]?.jsonPrimitive?.contentOrNull ?: "?"} " +
                "dnd=${data["dnd"]?.jsonPrimitive?.contentOrNull ?: "?"}"
        "pause_location_logging" ->
            "paused=${data["paused"]?.jsonPrimitive?.contentOrNull ?: "?"}"
        "clear_location_history" -> "Deleted location history"
        "pause_health_sync" ->
            "paused=${data["paused"]?.jsonPrimitive?.contentOrNull ?: "?"}"
        "clear_health_cache" -> "Deleted health cache"
        "phone_set_volume" -> {
            val changed = data["changed"]?.jsonPrimitive?.contentOrNull ?: ""
            val skipped = data["skipped"]?.jsonPrimitive?.contentOrNull ?: ""
            buildString {
                append(changed.ifEmpty { "no change" })
                if (skipped.isNotEmpty()) append(" skipped $skipped")
            }
        }
        else -> "OK"
    }

    fun logClock(detail: String, status: String) {
        write(category = "clock", title = "clock", detail = detail, status = status)
    }

    fun logNavigation(detail: String, status: String) {
        write(category = "navigation", title = "phone_navigate", detail = detail, status = status)
    }

    fun logTextSend(detail: String, status: String) {
        write(category = "text", title = "text", detail = detail, status = status)
    }

    fun logUpdate(detail: String, status: String = "info") {
        write(category = "update", title = "update", detail = detail, status = status)
    }

    fun logLocation(detail: String, status: String = "info") {
        write(category = "location", title = "location history", detail = detail, status = status)
    }

    fun logHealth(detail: String, status: String = "info") {
        write(category = "health", title = "sleep and health", detail = detail, status = status)
    }

    fun logRemoteAction(title: String, detail: String, status: String) {
        write(category = "tool", title = title, detail = detail, status = status)
    }
}
