package dev.phonepi.clock.schedule

/**
 * Idempotent command results. The phone stores these. A repeat of the same
 * requestId returns the first result. Entries older than 7 days drop off,
 * and the store keeps at most [maxEntries] (oldest first).
 */
class RequestLedger(
    private val maxEntries: Int = 1000,
    private val ttlMs: Long = SEVEN_DAYS_MS,
) {
    data class Entry(val requestId: String, val atMs: Long, val body: String)

    private val order = ArrayDeque<Entry>()
    private val byId = HashMap<String, Entry>()

    fun replay(requestId: String, nowMs: Long): String? {
        prune(nowMs)
        return byId[requestId]?.body
    }

    fun remember(requestId: String, nowMs: Long, body: String) {
        prune(nowMs)
        if (requestId in byId) return
        val entry = Entry(requestId, nowMs, body)
        order.addLast(entry)
        byId[requestId] = entry
        while (order.size > maxEntries) {
            val dropped = order.removeFirst()
            byId.remove(dropped.requestId)
        }
    }

    fun prune(nowMs: Long) {
        while (order.isNotEmpty() && nowMs - order.first().atMs > ttlMs) {
            val dropped = order.removeFirst()
            byId.remove(dropped.requestId)
        }
    }

    fun size(): Int = order.size

    fun snapshot(): List<Entry> = order.toList()

    fun restore(entries: List<Entry>) {
        order.clear()
        byId.clear()
        entries.sortedBy { it.atMs }.forEach { entry ->
            if (entry.requestId in byId) return@forEach
            order.addLast(entry)
            byId[entry.requestId] = entry
        }
    }

    companion object {
        const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
