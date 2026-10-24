package dev.phonepi.companion.clock

import android.content.Context
import dev.phonepi.companion.tools.ToolHandler
import dev.phonepi.companion.tools.toolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val CLOCK_TOOL_NAMES = listOf(
    "clock_list_alarms",
    "clock_get_alarm",
    "clock_health",
    "clock_history",
    "clock_create_alarm",
    "clock_update_alarm",
    "clock_delete_alarm",
    "clock_enable_alarm",
    "clock_disable_alarm",
    "clock_skip_next",
    "clock_unskip",
    "clock_snooze",
    "clock_dismiss",
)

fun clockTools(context: Context): List<ToolHandler> =
    CLOCK_TOOL_NAMES.map { ClockProxyTool(context, it) }

/**
 * Forwards one clock tool to the clock app ContentProvider.
 * The clock app owns the alarm. This process does not store a second copy.
 */
class ClockProxyTool(
    context: Context,
    override val name: String,
) : ToolHandler {
    private val app = context.applicationContext

    override suspend fun handle(params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val payload = ClockBridge.call(app, name, params.toString())
            ?: return@withContext toolError("PhonePi Clock is not installed or the signature does not match")
        ClockBridge.snapshotFrom(payload)?.let { ClockSnapshotSync.publish(it.toString()) }
        try {
            Json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            toolError("PhonePi Clock returned a bad payload")
        }
    }
}
