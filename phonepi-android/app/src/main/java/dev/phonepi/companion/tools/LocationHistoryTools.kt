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
        val paused = params["paused"]?.jsonPrimitive?.booleanOrNull
            ?: return toolError("paused must be true or false")
        LocationHistoryPrefs.setPaused(context, paused)
        if (paused) LocationCapture.stop(context) else LocationCapture.refresh(context)
        return success { put("paused", paused) }
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
