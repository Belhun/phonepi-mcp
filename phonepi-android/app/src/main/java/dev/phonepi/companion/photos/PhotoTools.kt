package dev.phonepi.companion.photos

import dev.phonepi.companion.tools.ToolHandler
import dev.phonepi.companion.tools.optionalString
import dev.phonepi.companion.tools.requireInt
import dev.phonepi.companion.tools.success
import dev.phonepi.companion.tools.toolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Metadata only. No image bytes. Rate-limited. Logged by ToolRouter.
 * Read-only: the library is queried, never updated.
 */
class GetRecentPhotosTool(
    private val gate: PhotoGate,
    private val library: PhotoLibrary,
) : ToolHandler {
    override val name = "get_recent_photos"

    override suspend fun handle(params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        gate.denial()?.let { return@withContext denied(it) }
        val decision = resolveLimit(params.requireInt("limit"))
        if (decision.error != null) return@withContext toolError(decision.error)
        val since = when (val parsed = params.readSince()) {
            SinceParam.Absent -> null
            SinceParam.Invalid ->
                return@withContext toolError("since must be epoch milliseconds >= 0")
            is SinceParam.Value -> parsed.ms
        }
        try {
            val rows = library.scan(PhotoLimits.SCAN_CAP, since)
            val selected = selectRecent(rows, decision.limit, since)
            success {
                put("count", selected.size)
                put("limit", decision.limit)
                if (decision.capped) {
                    put("limitCapped", true)
                    put("note", "limit is capped at ${PhotoLimits.MAX_LIMIT}")
                }
                if (since != null) put("since", since)
                put("scanned", rows.size.coerceAtMost(PhotoLimits.SCAN_CAP))
                put(
                    "photos",
                    buildJsonArray {
                        selected.forEach { add(it.toMetadataJson()) }
                    },
                )
            }
        } catch (_: SecurityException) {
            denied(securityDenial())
        }
    }

    private fun securityDenial(): PhotoDenial =
        gate.denial() ?: PhotoDenial(
            grantNeeded = "READ_MEDIA_IMAGES",
            error = "Photos permission not granted. Open PhonePi and tap Grant on Recent photos.",
        )
}

/**
 * One recompressed image as base64. Token-gated on the server command list.
 * The phone still rate-limits and logs the call. Original bytes are never returned.
 */
class GetPhotoTool(
    private val gate: PhotoGate,
    private val library: PhotoLibrary,
) : ToolHandler {
    override val name = "get_photo"

    override suspend fun handle(params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        gate.denial()?.let { return@withContext denied(it) }
        if (!params.containsKey("id")) return@withContext toolError("id is required")
        val id = params.readPhotoId()
        if (id == null || id <= 0L) return@withContext toolError("id must be a MediaStore image id")
        val dimension = params.readDimension()
        if (dimension is DimensionChoice.Invalid) {
            return@withContext toolError(
                "maxDimension must be ${PhotoLimits.MIN_REQUEST_EDGE}..${PhotoLimits.MAX_REQUEST_EDGE} or \"full\"",
            )
        }
        val quality = params.readQuality()
            ?: return@withContext toolError(
                "quality must be an integer from ${PhotoLimits.MIN_QUALITY} to ${PhotoLimits.MAX_QUALITY}",
            )
        val format = params.readFormat()
            ?: return@withContext toolError("format must be jpeg or webp")
        try {
            val record = library.find(id) ?: return@withContext toolError("Photo not found")
            val attempts = encodeAttempts(record.width, record.height, dimension, quality, format)
            val requested = attempts.first()
            var lastBytes = 0
            for (attempt in attempts) {
                val encoded = library.encode(id, attempt)
                    ?: return@withContext toolError("Could not decode photo $id")
                lastBytes = encoded.bytes.size
                val payload = photoPayload(
                    record = record,
                    encoded = encoded,
                    attempt = attempt,
                    requested = requested,
                    full = dimension is DimensionChoice.Full,
                )
                if (payload.toString().encodeToByteArray().size <= PhotoLimits.MAX_JSON_UTF8_BYTES) {
                    return@withContext payload
                }
            }
            toolError(
                "Photo is too large for a single response (smallest try was $lastBytes bytes). " +
                    "PhonePi has no multi-frame image transfer. The JSON response must stay under 1 MB.",
            )
        } catch (_: SecurityException) {
            denied(
                gate.denial() ?: PhotoDenial(
                    grantNeeded = "READ_MEDIA_IMAGES",
                    error = "Photos permission not granted. Open PhonePi and tap Grant on Recent photos.",
                ),
            )
        }
    }
}

internal fun denied(denial: PhotoDenial): JsonObject = buildJsonObject {
    put("status", "error")
    put("error", denial.error)
    put("grantNeeded", denial.grantNeeded)
}

internal fun PhotoRecord.toMetadataJson(): JsonObject = buildJsonObject {
    put("id", id.toString())
    put("displayName", displayName)
    if (dateTakenMs != null && dateTakenMs > 0L) {
        put("dateTaken", dateTakenMs)
    } else {
        put("dateTaken", JsonNull)
    }
    put("dateAdded", dateAddedMs)
    put("size", sizeBytes)
    put("width", width)
    put("height", height)
    put("mimeType", mimeType)
}

private fun photoPayload(
    record: PhotoRecord,
    encoded: EncodedPhoto,
    attempt: EncodeAttempt,
    requested: EncodeAttempt,
    full: Boolean,
): JsonObject {
    val capped = attempt.maxEdge < requested.maxEdge || attempt.quality < requested.quality
    val base64 = Base64.getEncoder().encodeToString(encoded.bytes)
    return success {
        put("id", record.id.toString())
        put("displayName", record.displayName)
        if (record.dateTakenMs != null && record.dateTakenMs > 0L) {
            put("dateTaken", record.dateTakenMs)
        } else {
            put("dateTaken", JsonNull)
        }
        put("dateAdded", record.dateAddedMs)
        put("size", record.sizeBytes)
        put("width", record.width)
        put("height", record.height)
        put("mimeType", record.mimeType)
        put("base64", base64)
        put("exifIncluded", false)
        put("exifNote", PhotoLimits.EXIF_NOTE)
        put(
            "encoding",
            buildJsonObject {
                put("mimeType", encoded.mimeType)
                put("width", encoded.width)
                put("height", encoded.height)
                put("quality", attempt.quality)
                put("byteLength", encoded.bytes.size)
                put("maxDimension", attempt.maxEdge)
                put("full", full)
                put("cappedToFit", capped)
            },
        )
    }
}

private sealed interface SinceParam {
    data object Absent : SinceParam
    data object Invalid : SinceParam
    data class Value(val ms: Long) : SinceParam
}

private fun JsonObject.readSince(): SinceParam {
    if (!containsKey("since")) return SinceParam.Absent
    val prim = this["since"] as? JsonPrimitive ?: return SinceParam.Invalid
    val value = prim.longOrNull ?: prim.contentOrNull?.trim()?.toLongOrNull() ?: return SinceParam.Invalid
    if (value < 0L) return SinceParam.Invalid
    return SinceParam.Value(value)
}

private fun JsonObject.readQuality(): Int? {
    if (!containsKey("quality")) return PhotoLimits.DEFAULT_QUALITY
    val n = requireInt("quality") ?: return null
    if (n !in PhotoLimits.MIN_QUALITY..PhotoLimits.MAX_QUALITY) return null
    return n
}

private fun JsonObject.readPhotoId(): Long? {
    val el = this["id"] ?: return null
    val prim = el as? JsonPrimitive ?: return null
    return prim.longOrNull ?: prim.contentOrNull?.trim()?.toLongOrNull()
}

private fun JsonObject.readDimension(): DimensionChoice {
    if (!containsKey("maxDimension")) return DimensionChoice.Default
    val prim = this["maxDimension"] as? JsonPrimitive ?: return DimensionChoice.Invalid
    val text = prim.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return DimensionChoice.Default
    if (text.equals("full", ignoreCase = true)) return DimensionChoice.Full
    val n = text.toIntOrNull() ?: return DimensionChoice.Invalid
    if (n < PhotoLimits.MIN_REQUEST_EDGE || n > PhotoLimits.MAX_REQUEST_EDGE) {
        return DimensionChoice.Invalid
    }
    return DimensionChoice.Pixels(n)
}

private fun JsonObject.readFormat(): PhotoFormat? {
    val raw = optionalString("format")?.trim()?.lowercase() ?: return PhotoFormat.JPEG
    return when (raw) {
        "", "jpeg", "jpg" -> PhotoFormat.JPEG
        "webp" -> PhotoFormat.WEBP
        else -> null
    }
}
