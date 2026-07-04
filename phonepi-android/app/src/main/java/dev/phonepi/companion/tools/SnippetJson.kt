package dev.phonepi.companion.tools

import dev.phonepi.companion.data.SnippetEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val SNIPPET_TYPES = setOf("note", "todo", "bookmark", "snippet", "draft")

fun normalizeTags(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .joinToString(",")
}

fun SnippetEntity.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("content", content)
    put("type", type)
    put("tags", tags)
}

fun snippetsArray(items: List<SnippetEntity>): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.buildJsonArray {
        items.forEach { add(it.toJson()) }
    }
