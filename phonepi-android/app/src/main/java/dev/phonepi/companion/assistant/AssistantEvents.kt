package dev.phonepi.companion.assistant

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.textsend.PhoneUplink
import org.json.JSONObject
import java.util.UUID

/**
 * Phone → box frames for notification replies and inbound prompts.
 * Queued until assistant_ack. Never includes the WebSocket token.
 */
object AssistantEvents {
    fun emitReply(
        context: Context,
        messageId: String,
        action: String,
        replyText: String?,
        ts: Long = System.currentTimeMillis(),
    ) {
        val id = "r-${messageId.take(32)}-${UUID.randomUUID()}".take(80)
        val json = JSONObject()
            .put("type", "notification_reply")
            .put("id", id)
            .put("requestId", id)
            .put("messageId", messageId)
            .put("action", action.take(AssistantProtocol.ACTION_MAX))
            .put("ts", ts)
        if (!replyText.isNullOrBlank()) {
            json.put("replyText", replyText.take(AssistantProtocol.PROMPT_MAX))
        }
        ActivityLog.logMessage("Notification $messageId $action", "success")
        AssistantOutbox.enqueue(context, json.toString())
    }

    fun emitPrompt(
        context: Context,
        text: String,
        source: String,
        ts: Long = System.currentTimeMillis(),
    ): String? {
        val clipped = AssistantProtocol.clipPrompt(text) ?: return null
        val id = AssistantProtocol.newId("prompt")
        val json = JSONObject()
            .put("type", "inbound_prompt")
            .put("id", id)
            .put("requestId", id)
            .put("text", clipped)
            .put("source", AssistantProtocol.normalizeSource(source))
            .put("ts", ts)
        ActivityLog.logMessage("Inbound prompt (${AssistantProtocol.normalizeSource(source)})", "success")
        AssistantOutbox.enqueue(context, json.toString())
        return id
    }

    fun clearAll(context: Context) {
        AssistantOutbox.clear(context)
        PhoneUplink.send?.invoke("""{"type":"assistant_cleared"}""")
        ActivityLog.logMessage("Deleted prompts and replies", "success")
    }
}
