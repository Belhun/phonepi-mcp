package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.assistant.AssistantParseResult
import dev.phonepi.companion.assistant.AssistantProtocol
import dev.phonepi.companion.data.MessageEntity
import dev.phonepi.companion.data.PhonePiDatabase
import dev.phonepi.companion.messaging.MessageNotificationHelper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class SendNotificationTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext
    private val dao = PhonePiDatabase.get(appContext).messageDao()

    override val name = "send_notification"

    override suspend fun handle(params: JsonObject): JsonObject {
        val parsed = AssistantProtocol.parseNotification(params)
        if (parsed is AssistantParseResult.Err) return toolError(parsed.error)
        val request = (parsed as AssistantParseResult.Ok).request
        val existing = dao.getById(request.requestId)
        if (existing != null) {
            return success {
                put("messageId", existing.messageId)
                put("notificationId", existing.messageId.hashCode())
                put("duplicate", true)
                put("state", existing.state)
            }
        }
        val now = System.currentTimeMillis()
        dao.insert(
            MessageEntity(
                messageId = request.requestId,
                title = request.title,
                body = request.body,
                state = "pending",
                response = null,
                responseIndex = null,
                respondedAt = null,
                responseOptions = dev.phonepi.companion.messaging.MessageRepository.encodeOptions(request.actions),
                requireResponse = request.actions.isNotEmpty(),
                expiresAt = null,
                createdAt = now,
            ),
        )
        MessageNotificationHelper.post(
            context = appContext,
            messageId = request.requestId,
            title = request.title,
            body = request.body,
            responseOptions = request.actions,
            priorityHigh = request.priorityHigh,
            includeReply = true,
            includeDismiss = false,
        )
        return success {
            put("messageId", request.requestId)
            put("notificationId", request.requestId.hashCode())
            put("duplicate", false)
            put("state", "pending")
        }
    }
}
