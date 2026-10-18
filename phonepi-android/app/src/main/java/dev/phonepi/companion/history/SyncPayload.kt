package dev.phonepi.companion.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true }

fun locationSyncMessage(events: List<LocationEventRecord>): String {
    val payload = buildJsonObject {
        put("type", "location_sync")
        put("events", buildJsonArray {
            events.forEach { add(eventJson(it)) }
        })
    }
    return payload.toString()
}

fun locationClearedMessage(): String = """{"type":"location_cleared"}"""

data class SyncAck(val accepted: List<String>, val rejected: List<String>)

fun parseSyncAck(text: String): SyncAck? {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "location_sync_ack") return null
    val accepted = stringList(obj["accepted"] as? JsonArray)
    val rejected = (obj["rejected"] as? JsonArray).orEmpty().mapNotNull { item ->
        (item as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
    }
    return SyncAck(accepted, rejected)
}

fun parseClearedAck(text: String): Boolean {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
    return obj["type"]?.jsonPrimitive?.contentOrNull == "location_cleared_ack"
}

private fun eventJson(event: LocationEventRecord): JsonObject = buildJsonObject {
    put("id", event.id)
    put("kind", event.kind)
    put("startMs", event.startMs)
    put("endMs", event.endMs?.let(::JsonPrimitive) ?: JsonNull)
    put("latitude", event.latitude)
    put("longitude", event.longitude)
    put("endLatitude", event.endLatitude?.let(::JsonPrimitive) ?: JsonNull)
    put("endLongitude", event.endLongitude?.let(::JsonPrimitive) ?: JsonNull)
    put("placeName", event.placeName?.let(::JsonPrimitive) ?: JsonNull)
    put("address", event.address?.let(::JsonPrimitive) ?: JsonNull)
    put("distanceMeters", event.distanceMeters?.let(::JsonPrimitive) ?: JsonNull)
    put("source", event.source)
    put("updatedAtMs", event.updatedAtMs)
}

private fun stringList(array: JsonArray?): List<String> =
    array.orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
