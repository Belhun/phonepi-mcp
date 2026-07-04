package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.util.NotificationHelper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class SendNotificationTool(private val context: Context) : ToolHandler {
    override val name = "send_notification"

    override suspend fun handle(params: JsonObject): JsonObject {
        val title = params.requireString("title") ?: return toolError("Missing required parameter: title")
        val body = params.requireString("body") ?: return toolError("Missing required parameter: body")
        val high = params.optionalString("priority") == "high"
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        NotificationHelper.post(
            context = context,
            id = id,
            channelId = if (high) NotificationHelper.CHANNEL_HIGH else NotificationHelper.CHANNEL_DEFAULT,
            title = title,
            body = body,
            priorityHigh = high,
        )
        return success {
            put("notificationId", id)
        }
    }
}
