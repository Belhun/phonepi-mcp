package dev.phonepi.companion.photos

data class LimitDecision(
    val limit: Int,
    val capped: Boolean,
    val error: String?,
)

data class RawPhotoColumns(
    val id: Long,
    val displayName: String?,
    val dateTakenRaw: Long,
    val dateAddedRaw: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
)

fun resolveLimit(requested: Int?): LimitDecision = when {
    requested == null -> LimitDecision(PhotoLimits.DEFAULT_LIMIT, capped = false, error = null)
    requested < 1 -> LimitDecision(
        PhotoLimits.DEFAULT_LIMIT,
        capped = false,
        error = "limit must be at least 1",
    )
    requested > PhotoLimits.MAX_LIMIT -> LimitDecision(
        PhotoLimits.MAX_LIMIT,
        capped = true,
        error = null,
    )
    else -> LimitDecision(requested, capped = false, error = null)
}

fun selectRecent(photos: List<PhotoRecord>, limit: Int, sinceMs: Long?): List<PhotoRecord> {
    val capped = limit.coerceIn(1, PhotoLimits.MAX_LIMIT)
    return photos
        .asSequence()
        .filter { photo -> sinceMs == null || photo.sortKey() >= sinceMs }
        .sortedWith(compareByDescending<PhotoRecord> { it.sortKey() }.thenByDescending { it.id })
        .distinctBy { it.id }
        .take(capped)
        .toList()
}

fun photoFromColumns(raw: RawPhotoColumns): PhotoRecord? {
    if (raw.id <= 0L) return null
    val mime = raw.mimeType?.trim()?.lowercase().orEmpty().ifEmpty { "image/*" }
    if (!mime.startsWith("image/")) return null
    return PhotoRecord(
        id = raw.id,
        displayName = sanitizeDisplayName(raw.displayName),
        dateTakenMs = raw.dateTakenRaw.takeIf { it > 0L },
        dateAddedMs = epochToMillis(raw.dateAddedRaw),
        sizeBytes = raw.sizeBytes.coerceAtLeast(0L),
        width = raw.width.coerceAtLeast(0),
        height = raw.height.coerceAtLeast(0),
        mimeType = mime,
    )
}

/** MediaStore DATE_ADDED is seconds. DATE_TAKEN is already milliseconds. */
fun epochToMillis(raw: Long): Long {
    if (raw <= 0L) return 0L
    return if (raw < 10_000_000_000L) raw * 1000L else raw
}

fun sanitizeDisplayName(raw: String?): String {
    val cleaned = raw.orEmpty().replace(Regex("[\\p{Cntrl}]"), "").trim()
    val name = cleaned.substringAfterLast('/').substringAfterLast('\\')
    return name.take(180).ifEmpty { "photo" }
}

/**
 * In-app share switch is separate from the OS grant. Both must be on.
 * Default for [sharingEnabled] is false.
 */
fun denialFor(sharingEnabled: Boolean, osGranted: Boolean, permission: String): PhotoDenial? {
    if (!sharingEnabled) {
        return PhotoDenial(
            grantNeeded = "photo_sharing",
            error = "Photo sharing is off. Open PhonePi, turn on Share recent photos, and grant Photos.",
        )
    }
    if (!osGranted) {
        return PhotoDenial(
            grantNeeded = permission,
            error = "Photos permission not granted ($permission). Open PhonePi and tap Grant on Recent photos.",
        )
    }
    return null
}
