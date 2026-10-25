package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.history.LocationCapture
import dev.phonepi.companion.history.LocationHistoryEngine
import dev.phonepi.companion.history.LocationHistoryPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PauseLocationLoggingTool(private val context: Context) : ToolHandler {
    override val name = "pause_location_logging"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (params["paused"]?.jsonPrimitive?.booleanOrNull != true) {
            return toolError("paused must be true; resume location on the phone")
        }
        LocationHistoryPrefs.setPaused(context, true)
        LocationCapture.stop(context)
        return success { put("paused", true) }
    }
}

class ClearLocationHistoryTool(private val context: Context) : ToolHandler {
    override val name = "clear_location_history"

    override suspend fun handle(params: JsonObject): JsonObject {
        val confirm = params.requireString("confirm")
        if (confirm != "delete") return toolError("confirm must be \"delete\"")
        LocationHistoryEngine.clear(context)
        return success { put("cleared", true) }
    }
}
