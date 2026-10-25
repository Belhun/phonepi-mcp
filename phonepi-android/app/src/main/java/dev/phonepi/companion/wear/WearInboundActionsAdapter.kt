package dev.phonepi.companion.wear

import android.content.Context
import dev.phonepi.companion.assistant.AssistantEvents
import dev.phonepi.companion.assistant.AssistantProtocol
import dev.phonepi.companion.log.ActivityLog

/**
 * Watch → phone inbound actions. [submitPrompt] uses the same
 * [AssistantEvents.emitPrompt] path as the Ask assistant tile so
 * `get_inbound_prompts` and the wake hook see watch prompts. Source is
 * always `watch_relay`. Quiet uses [WearQuietController] (DND access).
 *
 * The watch never receives PHONEPI_WS_TOKEN, TS_AUTHKEY, the send-gateway
 * token, or the bridge key. Do not log those values here.
 */
class WearInboundActionsAdapter(
    private val emitPrompt: (text: String, source: String) -> WearActionResult,
    private val quiet: (Int) -> WearActionResult,
    private val log: (String, String) -> Unit = { detail, status ->
        ActivityLog.logWear(detail, status)
    },
) : WearInboundActions {

    override suspend fun submitPrompt(text: String, source: String): WearActionResult {
        val clean = WearProtocol.sanitizePrompt(text)
            ?: return WearActionResult(false, "empty prompt")
        val clipped = AssistantProtocol.clipPrompt(clean)
            ?: return WearActionResult(false, "empty prompt")
        val mapped = toAssistantSource(source)
        val result = emitPrompt(clipped, mapped)
        val preview = clipped.take(LOG_PREVIEW)
        val status = if (result.ok) "success" else "error"
        log("Watch prompt ${result.message} (${clipped.length} chars): $preview", status)
        return result
    }

    override suspend fun quietForMinutes(minutes: Int): WearActionResult {
        val result = quiet(minutes)
        log("Watch quiet ${minutes}m: ${result.message}", if (result.ok) "success" else "error")
        return result
    }

    companion object {
        private const val LOG_PREVIEW = 120

        /** Watch origins all land as this allowlisted assistant source. */
        const val ASSISTANT_SOURCE = "watch_relay"

        fun toAssistantSource(@Suppress("UNUSED_PARAMETER") source: String): String =
            ASSISTANT_SOURCE

        fun create(context: Context): WearInboundActionsAdapter {
            val app = context.applicationContext
            return WearInboundActionsAdapter(
                emitPrompt = { text, source ->
                    val id = AssistantEvents.emitPrompt(app, text, source)
                    if (id == null) WearActionResult(false, "empty prompt")
                    else WearActionResult(true, "queued")
                },
                quiet = { minutes -> WearQuietController.quietFor(app, minutes) },
            )
        }
    }
}
