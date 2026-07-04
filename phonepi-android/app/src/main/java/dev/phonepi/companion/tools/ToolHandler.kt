package dev.phonepi.companion.tools

import kotlinx.serialization.json.JsonObject

/**
 * One tool. The returned JsonObject is the response "data" payload and must start
 * with a "status" field. Use the success/toolError helpers in Envelope.kt.
 */
interface ToolHandler {
    val name: String
    suspend fun handle(params: JsonObject): JsonObject
}
