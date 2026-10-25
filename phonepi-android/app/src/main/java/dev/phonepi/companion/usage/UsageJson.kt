package dev.phonepi.companion.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true }
private val DATE_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val SECRET_KEYS = setOf("token", "wstoken", "authorization", "password", "secret")

fun usageDayJson(day: UsageDay): String = buildJsonObject {
    put("date", day.date)
    put("timeZone", day.timeZone)
    put("screenOnMs", day.screenOnMs)
    put("unlockCount", day.unlockCount)
    put("lastUsedAt", day.lastUsedAt?.let(::JsonPrimitive) ?: JsonNull)
    put("updatedAtMs", day.updatedAtMs)
    put("apps", buildJsonArray {
        day.apps.forEach { app ->
            add(
                buildJsonObject {
                    put("package", app.packageName)
                    put("label", app.label)
                    put("foregroundMs", app.foregroundMs)
                    put("lastUsedAt", app.lastUsedAt)
                },
            )
        }
    })
}.toString()

fun usageMetaJson(meta: UsageMeta): JsonObject = buildJsonObject {
    put("enabled", meta.enabled)
    put("paused", meta.paused)
    put("usageAccessGranted", meta.usageAccessGranted)
    put("timeZone", meta.timeZone)
    put("lastUsedAt", meta.lastUsedAt?.let(::JsonPrimitive) ?: JsonNull)
    put("updatedAtMs", meta.updatedAtMs)
}

fun usageSyncMessage(meta: UsageMeta, days: List<UsageDay>): String = buildJsonObject {
    put("type", "usage_sync")
    put("meta", usageMetaJson(meta))
    put("days", buildJsonArray { days.forEach { add(json.parseToJsonElement(usageDayJson(it))) } })
}.toString()

fun usageClearedMessage(): String = """{"type":"usage_cleared"}"""

fun parseUsageDay(text: String): UsageDay? {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    return parseUsageDayObject(obj)
}

fun parseUsageDayObject(obj: JsonObject): UsageDay? {
    for (key in obj.keys) {
        if (key.lowercase() in SECRET_KEYS) return null
    }
    val date = obj["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (!DATE_RE.matches(date)) return null
    val timeZone = obj["timeZone"]?.jsonPrimitive?.contentOrNull?.trim()?.take(80).orEmpty()
    if (timeZone.isEmpty()) return null
    val screenOnMs = obj["screenOnMs"]?.jsonPrimitive?.longOrNull ?: return null
    val unlockCount = obj["unlockCount"]?.jsonPrimitive?.intOrNull ?: return null
    if (screenOnMs < 0L || unlockCount < 0) return null
    val lastUsedAt = obj["lastUsedAt"]?.jsonPrimitive?.longOrNull
    val updatedAtMs = obj["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: return null
    if (updatedAtMs < 0L) return null
    val apps = (obj["apps"] as? JsonArray).orEmpty().mapNotNull { item ->
        val app = item as? JsonObject ?: return@mapNotNull null
        for (key in app.keys) {
            if (key.lowercase() in SECRET_KEYS) return@mapNotNull null
        }
        val pkg = (app["package"] ?: app["packageName"])?.jsonPrimitive?.contentOrNull
            ?.trim()?.take(USAGE_PACKAGE_MAX).orEmpty()
        if (pkg.isEmpty()) return@mapNotNull null
        val label = app["label"]?.jsonPrimitive?.contentOrNull?.trim()?.take(USAGE_LABEL_MAX) ?: pkg
        val foregroundMs = app["foregroundMs"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        if (foregroundMs < 0L) return@mapNotNull null
        val appLast = app["lastUsedAt"]?.jsonPrimitive?.longOrNull ?: 0L
        UsageApp(pkg, label, foregroundMs, appLast)
    }
    return UsageDay(
        date = date,
        timeZone = timeZone,
        screenOnMs = screenOnMs,
        unlockCount = unlockCount,
        lastUsedAt = lastUsedAt,
        apps = apps.take(USAGE_MAX_APPS_STORED),
        updatedAtMs = updatedAtMs,
    )
}

data class UsageAck(val accepted: List<String>, val rejected: List<String>)

fun parseUsageAck(text: String): UsageAck? {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "usage_sync_ack") return null
    val accepted = (obj["accepted"] as? JsonArray).orEmpty().mapNotNull {
        it.jsonPrimitive.contentOrNull
    }
    val rejected = (obj["rejected"] as? JsonArray).orEmpty().mapNotNull { item ->
        (item as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: item.jsonPrimitive.contentOrNull
    }
    return UsageAck(accepted, rejected)
}

fun parseUsageClearedAck(text: String): Boolean {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
    return obj["type"]?.jsonPrimitive?.contentOrNull == "usage_cleared_ack"
}
