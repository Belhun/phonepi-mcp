package dev.phonepi.companion.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun JsonObject.requireString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

fun JsonObject.requireInt(key: String): Int? =
    this[key]?.jsonPrimitive?.let { p ->
        p.intOrNull ?: p.contentOrNull?.toIntOrNull()
    }

fun JsonObject.optionalLong(key: String): Long? =
    this[key]?.jsonPrimitive?.let { p ->
        p.longOrNull ?: p.contentOrNull?.toLongOrNull()
    }

fun JsonObject.optionalBool(key: String, default: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

fun JsonObject.requireObject(key: String): JsonObject? =
    this[key]?.jsonObject

fun JsonObject.requireArray(key: String): JsonArray? =
    this[key]?.jsonArray

fun JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

fun JsonElement.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

fun JsonElement.asBoolOrNull(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull
