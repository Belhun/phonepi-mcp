package dev.phonepi.companion.assistant

/**
 * Phone-side send policy for the assistant outbox.
 * Retryable acks reschedule one row; they never full-flush.
 * Flush stays under the server ingest cap (30/min) with a 20/min batch.
 */
object AssistantOutboxPolicy {
    const val FLUSH_BATCH = 20
    const val WINDOW_MS = 60_000L
    const val MAX_BACKOFF_MS = 300_000L
    const val DEFAULT_RETRY_AFTER_SEC = 60

    fun shouldDrop(ok: Boolean, retryable: Boolean): Boolean = ok || !retryable

    fun shouldFlushOnAck(ok: Boolean, retryable: Boolean): Boolean = false

    fun retryDelayMs(retryAfterSec: Int, attempt: Int, jitterMs: Int): Long {
        val baseMs = retryAfterSec.coerceAtLeast(1) * 1_000L
        val shift = attempt.coerceIn(0, 4)
        val exponential = baseMs * (1L shl shift)
        return (exponential + jitterMs.coerceAtLeast(0)).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun takeFlushBatch(
        lines: List<String>,
        sentInWindow: Int,
        batch: Int = FLUSH_BATCH,
    ): List<String> {
        val room = (batch - sentInWindow).coerceAtLeast(0)
        return lines.take(room)
    }

    fun nextFlushDelayMs(sentAt: List<Long>, nowMs: Long, windowMs: Long = WINDOW_MS): Long {
        if (sentAt.isEmpty()) return 0L
        val wait = windowMs - (nowMs - sentAt.first())
        return wait.coerceAtLeast(1L)
    }
}

internal interface AssistantOutboxScheduler {
    fun schedule(token: String, delayMs: Long, run: () -> Unit)
    fun cancel(token: String)
}

/** In-memory scheduler for tests and for the 40-queued convergence check. */
class RecordingOutboxScheduler : AssistantOutboxScheduler {
    data class Job(val token: String, val delayMs: Long, val run: () -> Unit)

    private val jobs = linkedMapOf<String, Job>()
    val scheduled = mutableListOf<Job>()

    override fun schedule(token: String, delayMs: Long, run: () -> Unit) {
        val job = Job(token, delayMs, run)
        jobs[token] = job
        scheduled += job
    }

    override fun cancel(token: String) {
        jobs.remove(token)
    }

    fun pending(): List<Job> = jobs.values.toList()

    fun runDue(nowDelayMs: Long = Long.MAX_VALUE) {
        val due = jobs.values.filter { it.delayMs <= nowDelayMs }
        due.forEach { jobs.remove(it.token) }
        due.forEach { it.run() }
    }
}
