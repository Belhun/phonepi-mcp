package dev.phonepi.companion.photos

import kotlin.math.max
import kotlin.math.roundToInt

sealed interface DimensionChoice {
    data object Default : DimensionChoice
    data object Full : DimensionChoice
    data class Pixels(val px: Int) : DimensionChoice
    data object Invalid : DimensionChoice
}

fun scaledSize(srcW: Int, srcH: Int, maxEdge: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return 1 to 1
    val edge = maxEdge.coerceAtLeast(1)
    val long = max(srcW, srcH)
    if (long <= edge) return srcW to srcH
    val scale = edge.toDouble() / long.toDouble()
    val w = (srcW * scale).roundToInt().coerceAtLeast(1)
    val h = (srcH * scale).roundToInt().coerceAtLeast(1)
    return w to h
}

/** Power-of-two sample so the decode stays near [maxEdge] before the exact scale. */
fun inSampleSize(srcW: Int, srcH: Int, maxEdge: Int): Int {
    if (srcW <= 0 || srcH <= 0 || maxEdge <= 0) return 1
    var sample = 1
    val long = max(srcW, srcH)
    while (sample < 64 && long / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    return sample
}

fun qualityLadder(requested: Int): List<Int> {
    val start = requested.coerceIn(PhotoLimits.MIN_QUALITY, PhotoLimits.MAX_QUALITY)
    return listOf(start, 70, 55, 40).filter { it <= start }.distinct()
}

fun edgeLadder(startEdge: Int): List<Int> {
    val edges = ArrayList<Int>(6)
    var edge = startEdge.coerceIn(PhotoLimits.MIN_REQUEST_EDGE, PhotoLimits.MAX_REQUEST_EDGE)
    repeat(6) {
        edges.add(edge)
        val next = (edge * 3) / 4
        if (next < PhotoLimits.MIN_REQUEST_EDGE || next >= edge) return edges
        edge = next
    }
    return edges
}

fun startEdge(srcW: Int, srcH: Int, choice: DimensionChoice): Int = when (choice) {
    DimensionChoice.Default -> PhotoLimits.DEFAULT_EDGE
    DimensionChoice.Invalid -> PhotoLimits.DEFAULT_EDGE
    is DimensionChoice.Pixels -> choice.px.coerceIn(
        PhotoLimits.MIN_REQUEST_EDGE,
        PhotoLimits.MAX_REQUEST_EDGE,
    )
    DimensionChoice.Full -> {
        val long = max(srcW, srcH)
        if (long <= 0) {
            PhotoLimits.DEFAULT_EDGE
        } else {
            long.coerceIn(PhotoLimits.MIN_REQUEST_EDGE, PhotoLimits.MAX_REQUEST_EDGE)
        }
    }
}

fun encodeAttempts(
    srcW: Int,
    srcH: Int,
    choice: DimensionChoice,
    quality: Int,
    format: PhotoFormat,
): List<EncodeAttempt> {
    val edges = edgeLadder(startEdge(srcW, srcH, choice))
    val qualities = qualityLadder(quality)
    return edges.flatMap { edge ->
        qualities.map { q -> EncodeAttempt(maxEdge = edge, quality = q, format = format) }
    }
}

/**
 * Largest raw byte count whose base64 form fits in [base64Budget] characters.
 * Base64 length is 4 * ceil(n / 3), with no line breaks.
 */
fun maxRawBytesForBase64(base64Budget: Int): Int {
    if (base64Budget < 4) return 0
    return (base64Budget / 4) * 3
}
