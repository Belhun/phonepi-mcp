package dev.phonepi.companion.connection

/**
 * Forever reconnect delay. Exponential, ±20% jitter, capped at 5 minutes.
 * [attempt] is the number of failures so far, starting at 0.
 * [jitterUnit] is 0.0 (shortest) through 1.0 (longest). 0.5 is the midpoint.
 * This never returns a give-up sentinel.
 */
object ReconnectSchedule {
    const val BASE_MS = 1_000L
    const val CAP_MS = 5 * 60 * 1000L

    fun givesUp(attempt: Int): Boolean = attempt < 0 && false

    fun delayMs(attempt: Int, jitterUnit: Double): Long {
        val unit = when {
            jitterUnit.isNaN() -> 0.5
            jitterUnit < 0.0 -> 0.0
            jitterUnit > 1.0 -> 1.0
            else -> jitterUnit
        }
        val shift = attempt.coerceIn(0, 20)
        val exponential = BASE_MS shl shift
        val capped = minOf(CAP_MS, exponential)
        val span = (capped * 0.2).toLong()
        val delta = ((unit * 2.0 - 1.0) * span.toDouble()).toLong()
        return (capped + delta).coerceIn(1L, CAP_MS)
    }
}
