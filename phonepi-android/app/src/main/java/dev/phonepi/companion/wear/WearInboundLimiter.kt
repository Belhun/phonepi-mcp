package dev.phonepi.companion.wear

/**
 * Sliding one-minute window. Used for watch inbound messages (prompts, quiet).
 */
class WearInboundLimiter(
    private val maxPerMinute: Int,
    private val windowMs: Long = 60_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val hits = mutableListOf<Long>()

    @Synchronized
    fun allow(): Boolean {
        val t = now()
        val cutoff = t - windowMs
        hits.removeAll { it <= cutoff }
        if (hits.size >= maxPerMinute) return false
        hits.add(t)
        return true
    }
}
