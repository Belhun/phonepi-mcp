package dev.phonepi.companion.textsend

import android.content.Context
import dev.phonepi.companion.tools.ToolHandler
import dev.phonepi.companion.tools.requireString
import dev.phonepi.companion.tools.toolError
import kotlinx.serialization.json.JsonObject

class RequestSendTextTool(private val context: Context) : ToolHandler {
    override val name = "request_send_text"

    override suspend fun handle(params: JsonObject): JsonObject {
        val recipient = params.requireString("recipient")
            ?: return toolError("recipient must be one contact name or one phone number")
        val message = params["message"]?.let { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: return toolError("message must be a string of at most 1000 characters")
        val mode = params.requireString("mode")
        return SendTextController.request(context, recipient, message, mode)
    }
}

class GetSendStatusTool(private val context: Context) : ToolHandler {
    override val name = "get_send_status"

    override suspend fun handle(params: JsonObject): JsonObject {
        val id = params.requireString("id") ?: return toolError("id must be the pending send id")
        return SendTextController.status(context, id)
    }
}
