package dev.phonepi.companion.presence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true }

fun presenceSyncMessage(snapshot: PresenceSnapshot): String =
    buildJsonObject {
        put("type", "presence_sync")
        put("snapshot", snapshotJson(snapshot))
    }.toString()

fun presenceClearedMessage(): String = """{"type":"presence_cleared"}"""

fun parsePresenceSyncAck(text: String): Boolean {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
    return obj["type"]?.jsonPrimitive?.contentOrNull == "presence_sync_ack"
}

fun parsePresenceClearedAck(text: String): Boolean {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
    return obj["type"]?.jsonPrimitive?.contentOrNull == "presence_cleared_ack"
}

private fun snapshotJson(snapshot: PresenceSnapshot): JsonObject = buildJsonObject {
    put("capturedAtMs", snapshot.capturedAtMs)
    put("enabled", snapshot.enabled)
    put("paused", snapshot.paused)
    put("place", snapshot.place)
    put("minutesAtPlace", snapshot.minutesAtPlace)
    put("arrivedAtMs", snapshot.arrivedAtMs?.let(::JsonPrimitive) ?: JsonNull)
    put("driving", snapshot.driving)
    put("moving", snapshot.moving)
    put("activity", snapshot.activity)
    put("dnd", snapshot.dnd)
    put("pi_quiet", snapshot.piQuiet)
    put("latitude", snapshot.latitude?.let(::JsonPrimitive) ?: JsonNull)
    put("longitude", snapshot.longitude?.let(::JsonPrimitive) ?: JsonNull)
    put("accuracyMeters", snapshot.accuracyMeters?.let(::JsonPrimitive) ?: JsonNull)
    put("lastEvent", snapshot.lastEvent?.let(::JsonPrimitive) ?: JsonNull)
    put("lastEventPlace", snapshot.lastEventPlace?.let(::JsonPrimitive) ?: JsonNull)
    put("lastEventMs", snapshot.lastEventMs?.let(::JsonPrimitive) ?: JsonNull)
    put("source", snapshot.source)
}
