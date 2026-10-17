package dev.phonepi.companion.photos

/**
 * Read-only photo contract. Callers list metadata, then fetch one image.
 * Nothing in this package deletes, moves, or edits MediaStore rows.
 */
data class PhotoRecord(
    val id: Long,
    val displayName: String,
    val dateTakenMs: Long?,
    val dateAddedMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
) {
    fun sortKey(): Long = dateTakenMs?.takeIf { it > 0L } ?: dateAddedMs
}

data class PhotoDenial(
    val grantNeeded: String,
    val error: String,
)

fun interface PhotoGate {
    fun denial(): PhotoDenial?
}

enum class PhotoFormat {
    JPEG,
    WEBP,
    ;

    fun mimeType(): String = when (this) {
        JPEG -> "image/jpeg"
        WEBP -> "image/webp"
    }
}

data class EncodeAttempt(
    val maxEdge: Int,
    val quality: Int,
    val format: PhotoFormat,
)

data class EncodedPhoto(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

interface PhotoLibrary {
    /** Newest images the store can see, already bounded by [scanCap]. */
    fun scan(scanCap: Int, sinceMs: Long?): List<PhotoRecord>

    fun find(id: Long): PhotoRecord?

    /**
     * Decode and recompress. Returned bytes are a new bitmap encoding.
     * Original EXIF, including GPS, is not copied.
     */
    fun encode(id: Long, attempt: EncodeAttempt): EncodedPhoto?
}

object PhotoLimits {
    const val DEFAULT_LIMIT = 5
    const val MAX_LIMIT = 20
    const val SCAN_CAP = 100
    const val DEFAULT_EDGE = 1600
    const val MIN_REQUEST_EDGE = 64
    const val MAX_REQUEST_EDGE = 4096
    const val DEFAULT_QUALITY = 80
    const val MIN_QUALITY = 40
    const val MAX_QUALITY = 95
    /** Compact tool JSON stays under this so the WebSocket frame stays under 1 MB. */
    const val MAX_JSON_UTF8_BYTES = 900_000
    const val LIST_PER_MINUTE = 30
    const val PHOTO_PER_MINUTE = 12

    const val EXIF_NOTE =
        "Returned bytes are a new JPEG or WebP. Original EXIF, including GPS location, " +
            "camera make, and the orientation tag, is not copied. Orientation is applied " +
            "to the pixels so the picture is upright. The pixels themselves can still show a place."
}
