package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.ui.TimerBridgeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * Sets a timer in Google Clock / system Clock when possible.
 * Waits for [TimerBridgeActivity] to confirm the OS accepted the intent.
 */
class TimerTool(private val context: Context) : ToolHandler {
    override val name = "set_timer"

    override suspend fun handle(params: JsonObject): JsonObject {
        val seconds = params.requireInt("seconds") ?: return toolError("Missing required parameter: seconds")
        if (seconds < 1) return toolError("seconds must be at least 1")

        val label = params.optionalString("message")
            ?: params.optionalString("label")
            ?: "PhonePi timer"
        val skipUi = params.optionalBool("skipUi", default = false)
        val identifier = "timer-${System.currentTimeMillis()}"
        val firesAt = Instant.now().plusSeconds(seconds.toLong()).toString()
        val token = UUID.randomUUID().toString()
        val deferred = TimerHandoffCoordinator.register(token)

        val bridgeStarted = withContext(Dispatchers.Main) {
            try {
                context.startActivity(
                    TimerBridgeActivity.intent(context, seconds, label, skipUi, token),
                )
                true
            } catch (e: Exception) {
                ActivityLog.logTimerHandoff(
                    "Bridge activity failed to start: ${e.message}",
                    "error",
                )
                false
            }
        }

        if (bridgeStarted) {
            val handoff = withTimeoutOrNull(HANDOFF_TIMEOUT_MS) { deferred.await() }
            if (handoff != null) {
                return buildResponse(seconds, label, skipUi, identifier, firesAt, handoff)
            }
            TimerHandoffCoordinator.cancel(token)
            ActivityLog.logTimerHandoff("Handoff timed out after ${HANDOFF_TIMEOUT_MS}ms", "error")
            return toolError("Timer handoff timed out — unlock the phone and try again")
        }

        return handleDirectLaunch(seconds, label, skipUi, identifier, firesAt)
    }

    private suspend fun handleDirectLaunch(
        seconds: Int,
        label: String,
        skipUi: Boolean,
        identifier: String,
        firesAt: String,
    ): JsonObject = withContext(Dispatchers.Main) {
        val launch = ClockTimer.launch(context, seconds, label, skipUi)
        if (launch.success) {
            ActivityLog.logTimerHandoff(
                "Direct clock launch (no bridge): target=${launch.usedTarget ?: "generic"}",
                "success",
            )
            buildResponse(
                seconds,
                label,
                skipUi,
                identifier,
                firesAt,
                TimerHandoffResult(TimerVia.CLOCK_APP),
            )
        } else {
            val fallbackFiresAt = TimerFallback.schedule(context, seconds, label)
            ActivityLog.logTimerHandoff(
                "Bridge and direct launch failed (${launch.failureReason}); alarm fallback",
                "warn",
            )
            buildResponse(
                seconds,
                label,
                skipUi,
                identifier,
                fallbackFiresAt,
                TimerHandoffResult(TimerVia.ALARM_MANAGER, launch.failureReason),
            )
        }
    }

    private fun buildResponse(
        seconds: Int,
        label: String,
        skipUi: Boolean,
        identifier: String,
        firesAt: String,
        handoff: TimerHandoffResult,
    ): JsonObject {
        val via = when (handoff.via) {
            TimerVia.CLOCK_APP -> "clock_app"
            TimerVia.ALARM_MANAGER -> "alarm_manager"
        }
        return success {
            put(
                "message",
                when (handoff.via) {
                    TimerVia.CLOCK_APP -> if (skipUi) {
                        "Clock accepted $seconds s timer (silent mode)"
                    } else {
                        "Clock opened with $seconds s timer — confirm or tap Start in Clock"
                    }
                    TimerVia.ALARM_MANAGER -> if (handoff.detail != null) {
                        "Clock could not open ($seconds s): ${handoff.detail}; PhonePi notification scheduled"
                    } else {
                        "Clock could not open; scheduled PhonePi notification instead"
                    }
                },
            )
            put("identifier", identifier)
            put("seconds", seconds)
            put("firesAt", firesAt)
            put("via", via)
            put("skipUi", skipUi)
            handoff.detail?.let { put("handoffDetail", it) }
        }
    }

    companion object {
        private const val HANDOFF_TIMEOUT_MS = 8_000L
    }
}
