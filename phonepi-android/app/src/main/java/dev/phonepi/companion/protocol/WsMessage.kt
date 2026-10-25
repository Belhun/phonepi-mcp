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

    /** Ack for a location sync or a history wipe. The raw frame is parsed by the sync helper. */
    data class LocationAck(val raw: String) : WsMessage

    /** Ack for a usage sync or a screen-time wipe. */
    data class UsageAck(val raw: String) : WsMessage

    /** A box-side read that should show up in Recent activity. */
    data class ActivityNote(
        val title: String,
        val detail: String,
        val status: String,
    ) : WsMessage

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
                "location_sync_ack", "location_cleared_ack" -> LocationAck(text)
                "usage_sync_ack", "usage_cleared_ack" -> UsageAck(text)
                "activity" -> ActivityNote(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull?.take(80) ?: "activity",
                    detail = obj["detail"]?.jsonPrimitive?.contentOrNull?.take(240).orEmpty(),
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "info",
                )
                else -> Unknown(obj["type"]?.jsonPrimitive?.contentOrNull ?: "none")
            }
        }
    }
}
