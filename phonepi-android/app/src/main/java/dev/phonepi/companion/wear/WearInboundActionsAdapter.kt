package dev.phonepi.companion.wear

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.textsend.PhoneUplink
import java.util.UUID

/**
 * INTEGRATION ADAPTER — cursor/phonepi-notif-actions
 *
 * Wire [submitPrompt] to that agent's inbound-prompt queue once
 * `get_inbound_prompts` and the phone-side store exist. Wire
 * [quietForMinutes] to its quiet/DND helper if it owns that path.
 *
 * Until then:
 * - [submitPrompt] sends a WebSocket frame `{"type":"inbound_prompt",...}`
 *   over the existing phone socket (already authenticated). The frame does
 *   not include the PhonePi token. If the socket is down, the frame is
 *   queued and flushed on the next connect.
 * - [quietForMinutes] uses [WearQuietController] (NotificationManager
 *   interruption filter) when Do Not Disturb access is granted.
 *
 * The watch never receives PHONEPI_WS_TOKEN, TS_AUTHKEY, the send-gateway
 * token, or the bridge key. Do not log those values here.
 */
class WearInboundActionsAdapter(
    private val sendFrame: (String) -> Boolean,
    private val enqueueFrame: (String) -> Unit,
    private val quiet: (Int) -> WearActionResult,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { "wear-" + UUID.randomUUID() },
    private val log: (String, String) -> Unit = { detail, status ->
        ActivityLog.logWear(detail, status)
    },
) : WearInboundActions {

    override suspend fun submitPrompt(text: String, source: String): WearActionResult {
        val clean = WearProtocol.sanitizePrompt(text)
            ?: return WearActionResult(false, "empty prompt")
        val frame = WearProtocol.inboundPromptFrame(
            id = newId(),
            text = clean,
            source = WearProtocol.normalizeSource(source),
            createdAt = nowMs(),
        )
        val handedOff = sendFrame(frame)
        if (!handedOff) enqueueFrame(frame)
        val preview = clean.take(LOG_PREVIEW)
        val status = if (handedOff) "success" else "info"
        val where = if (handedOff) "forwarded" else "queued"
        log("Watch prompt $where (${clean.length} chars): $preview", status)
        return WearActionResult(true, where)
    }

    override suspend fun quietForMinutes(minutes: Int): WearActionResult {
        val result = quiet(minutes)
        log("Watch quiet ${minutes}m: ${result.message}", if (result.ok) "success" else "error")
        return result
    }

    companion object {
        private const val LOG_PREVIEW = 120

        fun create(context: Context): WearInboundActionsAdapter {
            val app = context.applicationContext
            return WearInboundActionsAdapter(
                sendFrame = { frame -> PhoneUplink.send?.invoke(frame) == true },
                enqueueFrame = { frame -> WearPromptOutbox.enqueue(app, frame) },
                quiet = { minutes -> WearQuietController.quietFor(app, minutes) },
            )
        }
    }
}
