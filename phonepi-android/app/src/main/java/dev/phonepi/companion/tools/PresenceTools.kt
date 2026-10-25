package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.presence.PresenceCapture
import dev.phonepi.companion.presence.PresenceEngine
import dev.phonepi.companion.presence.PresencePrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PausePresenceLoggingTool(private val context: Context) : ToolHandler {
    override val name = "pause_presence_logging"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (params["paused"]?.jsonPrimitive?.booleanOrNull != true) {
            return toolError("paused must be true; resume presence on the phone")
        }
        PresencePrefs.setPaused(context, true)
        PresenceCapture.stop(context)
        PresenceEngine.nudge(context)
        return success { put("paused", true) }
    }
}

class ClearPresenceHistoryTool(private val context: Context) : ToolHandler {
    override val name = "clear_presence_history"

    override suspend fun handle(params: JsonObject): JsonObject {
        val confirm = params.requireString("confirm")
        if (confirm != "delete") return toolError("confirm must be \"delete\"")
        PresenceEngine.clear(context)
        return success { put("cleared", true) }
    }
}
