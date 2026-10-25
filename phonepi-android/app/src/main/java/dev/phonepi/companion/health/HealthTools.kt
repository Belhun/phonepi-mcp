package dev.phonepi.companion.health

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.tools.ToolHandler
import dev.phonepi.companion.tools.requireString
import dev.phonepi.companion.tools.success
import dev.phonepi.companion.tools.toolError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PauseHealthSyncTool(private val context: Context) : ToolHandler {
    override val name = "pause_health_sync"

    override suspend fun handle(params: JsonObject): JsonObject {
        val paused = params["paused"]?.jsonPrimitive?.booleanOrNull
            ?: return toolError("paused must be true or false")
        HealthConnectPrefs.setPaused(context, paused)
        if (paused) {
            ActivityLog.logHealth("Health sync paused", "info")
        } else {
            HealthSyncScheduler.ensure(context)
            HealthSync.run(context)
            ActivityLog.logHealth("Health sync resumed", "info")
        }
        HealthSync.nudge()
        return success { put("paused", paused) }
    }
}

class ClearHealthCacheTool(private val context: Context) : ToolHandler {
    override val name = "clear_health_cache"

    override suspend fun handle(params: JsonObject): JsonObject {
        val confirm = params.requireString("confirm")
        if (confirm != "delete") return toolError("confirm must be \"delete\"")
        HealthSync.requestClear(context)
        ActivityLog.logHealth("Deleted health cache", "success")
        return success { put("cleared", true) }
    }
}
