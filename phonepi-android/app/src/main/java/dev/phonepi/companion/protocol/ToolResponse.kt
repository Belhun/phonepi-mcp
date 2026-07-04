package dev.phonepi.companion.protocol

import dev.phonepi.companion.tools.toolError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One outbound response frame. The server resolves the pending MCP promise with
 * [data] directly, so [data] carries the {"status": ...} envelope.
 */
data class ToolResponse(
    val requestId: String,
    val data: JsonObject,
) {
    fun toJson(): String = buildJsonObject {
        put("type", "response")
        put("requestId", requestId)
        put("data", data)
    }.toString()

    companion object {
        fun error(requestId: String, message: String) =
            ToolResponse(requestId, toolError(message))
    }
}
