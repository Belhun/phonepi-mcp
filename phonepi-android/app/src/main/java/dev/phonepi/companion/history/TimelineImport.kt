package dev.phonepi.companion.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

private val json = Json { ignoreUnknownKeys = true }
private val latLngText = Regex("""(-?\d+(?:\.\d+)?)\s*°?\s*,\s*(-?\d+(?:\.\d+)?)\s*°?""")

data class TimelineImportResult(
    val events: List<LocationEventRecord>,
    val visits: Int,
    val drives: Int,
    val skipped: Int,
    val error: String?,
)

private const val MAX_IMPORT_EVENTS = 8000

fun parseTimelineExport(text: String): TimelineImportResult {
    val root = try {
        json.parseToJsonElement(text)
    } catch (_: Exception) {
        return emptyResult("That file is not JSON")
    }
    val obj = root as? JsonObject
        ?: return emptyResult("That file is not a Timeline export")
    val semantic = obj["semanticSegments"] as? JsonArray
    val legacy = obj["timelineObjects"] as? JsonArray
    if (semantic == null && legacy == null) {
        if (obj["locations"] is JsonArray) {
            return emptyResult(
                "This file is raw location points, not visits. Use Settings, Location, Location services, Timeline, Export Timeline data.",
            )
        }
        return emptyResult("That file has no semanticSegments or timelineObjects")
    }
    val parsed = mutableListOf<LocationEventRecord>()
    var skipped = 0
    if (semantic != null) {
        for (item in semantic) {
            val segment = item as? JsonObject
            if (segment == null) {
                skipped += 1
                continue
            }
            val event = semanticSegment(segment)
            if (event == null) skipped += 1 else parsed += event
        }
    }
    if (legacy != null) {
        for (item in legacy) {
            val segment = item as? JsonObject
            if (segment == null) {
                skipped += 1
                continue
            }
            val event = legacyObject(segment)
            if (event == null) skipped += 1 else parsed += event
        }
    }
    val newest = parsed.sortedByDescending { it.startMs }.take(MAX_IMPORT_EVENTS)
    val visits = newest.count { it.kind == "visit" }
    val drives = newest.count { it.kind == "drive" }
    return TimelineImportResult(
        events = newest,
        visits = visits,
        drives = drives,
        skipped = skipped,
        error = null,
    )
}

private fun semanticSegment(segment: JsonObject): LocationEventRecord? {
    val start = parseTime(segment["startTime"]) ?: return null
    val end = parseTime(segment["endTime"])
    val visit = segment["visit"] as? JsonObject
    if (visit != null) {
        val top = visit["topCandidate"] as? JsonObject
        val point = latLngOf(top?.get("placeLocation")) ?: latLngOf(visit["placeLocation"]) ?: return null
        val place = textOf(top, "name") ?: semanticLabel(textOf(top, "semanticType"))
        return visitRecord(start, end, point, place, textOf(top, "address"))
    }
    if (segment["timelinePath"] != null && segment["activity"] == null && segment["visit"] == null) {
        return null
    }
    val activity = segment["activity"] as? JsonObject ?: return null
    val startPoint = latLngOf(activity["start"]) ?: return null
    val endPoint = latLngOf(activity["end"]) ?: startPoint
    val distance = numberOf(activity["distanceMeters"]) ?: haversineMeters(
        startPoint.first,
        startPoint.second,
        endPoint.first,
        endPoint.second,
    )
    return driveRecord(start, end, startPoint, endPoint, distance)
}

private fun legacyObject(item: JsonObject): LocationEventRecord? {
    val visit = item["placeVisit"] as? JsonObject
    if (visit != null) {
        val location = visit["location"] as? JsonObject ?: return null
        val point = e7Point(location) ?: return null
        val duration = visit["duration"] as? JsonObject
        val start = parseTime(duration?.get("startTimestamp"))
            ?: parseTime(duration?.get("startTimestampMs"))
            ?: return null
        val end = parseTime(duration?.get("endTimestamp")) ?: parseTime(duration?.get("endTimestampMs"))
        return visitRecord(
            start,
            end,
            point,
            textOf(location, "name"),
            textOf(location, "address"),
        )
    }
    val activity = item["activitySegment"] as? JsonObject ?: return null
    val duration = activity["duration"] as? JsonObject
    val start = parseTime(duration?.get("startTimestamp"))
        ?: parseTime(duration?.get("startTimestampMs"))
        ?: return null
    val end = parseTime(duration?.get("endTimestamp")) ?: parseTime(duration?.get("endTimestampMs"))
    val startPoint = e7Point(activity["startLocation"] as? JsonObject) ?: return null
    val endPoint = e7Point(activity["endLocation"] as? JsonObject) ?: startPoint
    val distance = numberOf(activity["distance"]) ?: haversineMeters(
        startPoint.first,
        startPoint.second,
        endPoint.first,
        endPoint.second,
    )
    return driveRecord(start, end, startPoint, endPoint, distance)
}

private fun visitRecord(
    start: Long,
    end: Long?,
    point: Pair<Double, Double>,
    place: String?,
    address: String?,
): LocationEventRecord {
    val endMs = end?.takeIf { it >= start }
    return LocationEventRecord(
        id = timelineId("visit", start, point.first, point.second),
        kind = "visit",
        startMs = start,
        endMs = endMs,
        latitude = point.first,
        longitude = point.second,
        endLatitude = null,
        endLongitude = null,
        placeName = place?.take(120),
        address = address?.take(300),
        distanceMeters = null,
        source = "timeline",
        updatedAtMs = endMs ?: start,
    )
}

private fun driveRecord(
    start: Long,
    end: Long?,
    startPoint: Pair<Double, Double>,
    endPoint: Pair<Double, Double>,
    distance: Double,
): LocationEventRecord {
    val endMs = end?.takeIf { it >= start }
    return LocationEventRecord(
        id = timelineId("drive", start, startPoint.first, startPoint.second),
        kind = "drive",
        startMs = start,
        endMs = endMs,
        latitude = startPoint.first,
        longitude = startPoint.second,
        endLatitude = endPoint.first,
        endLongitude = endPoint.second,
        placeName = null,
        address = null,
        distanceMeters = distance.coerceAtLeast(0.0),
        source = "timeline",
        updatedAtMs = endMs ?: start,
    )
}

fun timelineId(kind: String, startMs: Long, lat: Double, lng: Double): String {
    val latE5 = (lat * 100_000.0).roundToLong()
    val lngE5 = (lng * 100_000.0).roundToLong()
    return "tl-$kind-$startMs-$latE5-$lngE5"
}

private fun semanticLabel(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw.equals("UNKNOWN", ignoreCase = true)) return null
    return raw.lowercase().replace('_', ' ').trim()
}

private fun latLngOf(node: kotlinx.serialization.json.JsonElement?): Pair<Double, Double>? {
    val obj = node as? JsonObject ?: return null
    val direct = pairFrom(obj["latLng"]) ?: pairFrom(obj["latlng"])
    if (direct != null) return direct
    val lat = numberOf(obj["latitude"])
    val lng = numberOf(obj["longitude"])
    if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
        return lat to lng
    }
    return e7Point(obj)
}

private fun pairFrom(node: kotlinx.serialization.json.JsonElement?): Pair<Double, Double>? {
    when (node) {
        is JsonPrimitive -> {
            val match = latLngText.find(node.content) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lng = match.groupValues[2].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return lat to lng
        }
        is JsonObject -> return latLngOf(node)
        else -> return null
    }
}

private fun e7Point(node: JsonObject?): Pair<Double, Double>? {
    if (node == null) return null
    val latE7 = numberOf(node["latitudeE7"]) ?: return null
    val lngE7 = numberOf(node["longitudeE7"]) ?: return null
    val lat = latE7 / 1e7
    val lng = lngE7 / 1e7
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}

private fun parseTime(node: kotlinx.serialization.json.JsonElement?): Long? {
    val primitive = node as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { return it }
    primitive.doubleOrNull?.let { value ->
        if (value > 1_000_000_000_000.0) return value.toLong()
    }
    val text = primitive.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return it }
    try {
        return OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        // Fall through to Instant.
    }
    return try {
        java.time.Instant.parse(text).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun textOf(obj: JsonObject?, key: String): String? {
    val text = obj?.get(key)?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    return text.takeIf { it.isNotEmpty() }
}

private fun numberOf(node: kotlinx.serialization.json.JsonElement?): Double? {
    val primitive = node as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
}

private fun emptyResult(error: String) = TimelineImportResult(
    events = emptyList(),
    visits = 0,
    drives = 0,
    skipped = 0,
    error = error,
)
