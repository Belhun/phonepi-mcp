package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.alarm.AlarmRequests
import dev.phonepi.companion.alarm.ClockActionLauncher
import dev.phonepi.companion.alarm.ClockResponses
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * Starts a Clock countdown with ACTION_SET_TIMER and SKIP_UI.
 * Replaces the old bridge-plus-AlarmManager fallback so bots get an honest result.
 */
class TimerTool(private val context: Context) : ToolHandler {
    override val name = "set_timer"

    override suspend fun handle(params: JsonObject): JsonObject {
        val request = try {
            AlarmRequests.setTimer(params)
        } catch (e: IllegalArgumentException) {
            return ClockResponses.validationError(e.message ?: "Invalid timer")
        }
        val result = ClockActionLauncher.setTimer(context, request)
        return ClockResponses.payload(result, ClockResponses.SET_TIMER_NOTE) {
            put("seconds", request.seconds)
            put("skipUi", true)
            request.message?.let { put("label", it) }
        }
    }
}
