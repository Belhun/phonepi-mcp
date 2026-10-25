package dev.phonepi.companion.assistant

import android.content.Context
import dev.phonepi.companion.tools.ToolHandler
import dev.phonepi.companion.tools.requireString
import dev.phonepi.companion.tools.success
import dev.phonepi.companion.tools.toolError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class ClearAssistantEventsTool(private val context: Context) : ToolHandler {
    override val name = "clear_assistant_events"

    override suspend fun handle(params: JsonObject): JsonObject {
        val confirm = params.requireString("confirm")
        if (confirm != "delete") return toolError("confirm must be \"delete\"")
        AssistantEvents.clearAll(context)
        return success { put("cleared", true) }
    }
}
