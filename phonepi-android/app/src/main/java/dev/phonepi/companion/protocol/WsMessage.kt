package dev.phonepi.companion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

/**
 * One inbound frame from the server. The server speaks the custom PhonePi JSON
 * protocol, not MCP. See research/01-protocol/websocket-spec.md.
 */
sealed interface WsMessage {

    /** Server keepalive probe. Reply with {"type":"pong"} at once. */
    data object Ping : WsMessage

    /** Reply to a phone-side keepalive ping. Proves the socket is alive. */
    data object Pong : WsMessage

    /** A tool call to run on the device. Echo [requestId] in the response. */
    data class Request(
        val requestId: String,
        val tool: String,
        val params: JsonObject,
    ) : WsMessage

    /** Server-side protocol error (for example malformed JSON we sent). */
    data class ServerError(val error: String) : WsMessage

    /** Any other or unparseable frame. Ignored by the message loop. */
    data class Unknown(val type: String) : WsMessage

    companion object {
        private val EMPTY = JsonObject(emptyMap())

        fun parse(text: String): WsMessage {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return Unknown("malformed")
            return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "ping" -> Ping
                "pong" -> Pong
                "request" -> Request(
                    requestId = obj["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    tool = obj["tool"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    params = (obj["params"] as? JsonObject) ?: EMPTY,
                )
                "error" -> ServerError(obj["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error")
                else -> Unknown(obj["type"]?.jsonPrimitive?.contentOrNull ?: "none")
            }
        }
    }
}
