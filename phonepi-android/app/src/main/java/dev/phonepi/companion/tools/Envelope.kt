package dev.phonepi.companion.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Build a success payload: {"status":"success", ...fields}. */
fun success(fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("status", "success")
    fields()
}

/** Build an error payload: {"status":"error","error": message}. */
fun toolError(message: String): JsonObject = buildJsonObject {
    put("status", "error")
    put("error", message)
}
