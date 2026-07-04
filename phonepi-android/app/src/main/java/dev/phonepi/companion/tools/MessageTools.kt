package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.data.MessageEntity
import dev.phonepi.companion.data.PhonePiDatabase
import dev.phonepi.companion.messaging.MessageNotificationHelper
import dev.phonepi.companion.messaging.MessageRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class SendMessageTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext
    private val dao = PhonePiDatabase.get(appContext).messageDao()

    override val name = "send_message"

    override suspend fun handle(params: JsonObject): JsonObject {
        val title = params.requireString("title") ?: return toolError("Missing required parameter: title")
        val body = params.requireString("body") ?: return toolError("Missing required parameter: body")
        val expiresIn = params.requireInt("expiresIn") ?: 0
        val requireResponse = params.optionalBool("requireResponse", default = false)
        val responseOptions = parseResponseOptions(params)
        val messageId = "msg-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val expiresAt = if (expiresIn > 0) now + expiresIn * 60_000L else null
        val priority = params.optionalString("priority") ?: "normal"
        val priorityHigh = priority in setOf("high", "urgent")

        val entity = MessageEntity(
            messageId = messageId,
            title = title,
            body = body,
            state = "pending",
            response = null,
            responseIndex = null,
            respondedAt = null,
            responseOptions = MessageRepository.encodeOptions(responseOptions),
            requireResponse = requireResponse,
            expiresAt = expiresAt,
            createdAt = now,
        )
        dao.insert(entity)

        MessageNotificationHelper.post(
            context = appContext,
            messageId = messageId,
            title = title,
            body = body,
            responseOptions = responseOptions,
            priorityHigh = priorityHigh,
        )

        return success {
            put("messageId", messageId)
            put("state", "pending")
            if (responseOptions.isNotEmpty()) {
                put("responseOptions", responseOptions.joinToString(", "))
            }
        }
    }

    private fun parseResponseOptions(params: JsonObject): List<String> {
        params["responseOptions"]?.jsonArray?.let { array ->
            val parsed = array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
            if (parsed.isNotEmpty()) return parsed
        }
        params.optionalString("responseOptions")?.let { raw ->
            if (raw.trimStart().startsWith("[")) {
                try {
                    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonArray
                        .mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                        .filter { it.isNotEmpty() }
                    if (parsed.isNotEmpty()) return parsed
                } catch (_: Exception) {
                }
            }
            val parsed = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }
}

class GetMessageResponseTool(private val context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).messageDao()

    override val name = "get_message_response"

    override suspend fun handle(params: JsonObject): JsonObject {
        val messageId = params.requireString("messageId")
            ?: return toolError("Missing required parameter: messageId")
        val row = dao.getById(messageId)
            ?: return success {
                put("state", "not_found")
                put("messageId", messageId)
            }

        val now = System.currentTimeMillis()
        if (row.state == "pending" && row.expiresAt != null && now > row.expiresAt) {
            val expired = row.copy(state = "expired")
            dao.update(expired)
            MessageNotificationHelper.cancel(context.applicationContext, messageId)
            return success {
                put("messageId", messageId)
                put("state", "expired")
            }
        }

        return success {
            put("messageId", messageId)
            put("state", row.state)
            row.response?.let { put("response", it) }
            row.responseIndex?.let { put("responseIndex", it) }
            row.respondedAt?.let { put("respondedAt", it) }
        }
    }
}
