package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.findphone.FindPhoneService
import dev.phonepi.companion.findphone.FindPhoneSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Stops a looping find-phone alarm started by [FindPhoneTool]. */
class StopAlarmTool(private val context: Context) : ToolHandler {
    override val name = "stop_alarm"

    override suspend fun handle(params: JsonObject): JsonObject {
        val sessionId = params.optionalString("sessionId")?.takeIf { it.isNotBlank() }
        val active = FindPhoneSession.activeSessionId
        if (active == null) {
            return toolError("No find-phone alarm is ringing")
        }
        if (sessionId != null && sessionId != active) {
            return toolError("No active session for id $sessionId (active: $active)")
        }
        FindPhoneService.stop(context, sessionId ?: active)
        return success {
            put("message", "Find phone alarm stopped")
            put("sessionId", sessionId ?: active)
        }
    }
}
