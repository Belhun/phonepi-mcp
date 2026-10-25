package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.usage.UsageEngine
import dev.phonepi.companion.usage.UsagePrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PauseUsageLoggingTool(private val context: Context) : ToolHandler {
    override val name = "pause_usage_logging"

    override suspend fun handle(params: JsonObject): JsonObject {
        val paused = params["paused"]?.jsonPrimitive?.booleanOrNull
            ?: return toolError("paused must be true or false")
        UsagePrefs.setPaused(context, paused)
        if (!paused) UsageEngine.refresh(context)
        ActivityLog.logUsage(
            if (paused) "Screen time paused" else "Screen time resumed",
            "info",
        )
        return success { put("paused", paused) }
    }
}

class ClearUsageHistoryTool(private val context: Context) : ToolHandler {
    override val name = "clear_usage_history"

    override suspend fun handle(params: JsonObject): JsonObject {
        val confirm = params.requireString("confirm")
        if (confirm != "delete") return toolError("confirm must be \"delete\"")
        UsageEngine.clear(context)
        return success { put("cleared", true) }
    }
}
