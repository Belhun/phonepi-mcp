package dev.phonepi.companion.messaging

import android.content.Context
import dev.phonepi.companion.data.MessageEntity
import dev.phonepi.companion.data.PhonePiDatabase
import dev.phonepi.companion.log.ActivityLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object MessageRepository {
    private fun dao(context: Context) = PhonePiDatabase.get(context.applicationContext).messageDao()

    suspend fun get(context: Context, messageId: String): MessageEntity? =
        dao(context).getById(messageId)

    suspend fun recordResponse(
        context: Context,
        messageId: String,
        response: String,
        responseIndex: Int,
    ): Boolean {
        val row = dao(context).getById(messageId) ?: return false
        if (row.state != "pending") return false
        dao(context).update(
            row.copy(
                state = "responded",
                response = response,
                responseIndex = responseIndex,
                respondedAt = System.currentTimeMillis(),
            ),
        )
        ActivityLog.logMessage("Message $messageId answered: $response", "success")
        MessageNotificationHelper.cancel(context, messageId)
        return true
    }

    suspend fun recordDismiss(context: Context, messageId: String): Boolean {
        val row = dao(context).getById(messageId) ?: return false
        if (row.state != "pending") return false
        dao(context).update(
            row.copy(
                state = "dismissed",
                respondedAt = System.currentTimeMillis(),
            ),
        )
        ActivityLog.logMessage("Message $messageId dismissed", "info")
        MessageNotificationHelper.cancel(context, messageId)
        return true
    }

    fun encodeOptions(options: List<String>): String? {
        if (options.isEmpty()) return null
        return buildJsonArray { options.forEach { add(JsonPrimitive(it)) } }.toString()
    }

    fun decodeOptions(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (_: Exception) {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
