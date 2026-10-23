package dev.phonepi.companion.textsend

/**
 * What happened to one attempt to hand a frame to the PhonePi server.
 * Only [NOT_SENT] is retried: the frame clearly never left the phone
 * (no socket, link not connected, connect failure, or timed out before
 * the server could have seen it). Anything the socket accepted, or the
 * server acked, is never resent, so a retry cannot double-log or double-send.
 */
enum class SendOutcome { NOT_SENT, HANDED_OFF, ACKED }

/** Retry after 2 s, 5 s, then 10 s (17 s total, which covers the 15 s HP → Box failover), then give up. */
object SendRetryPolicy {
    val DELAYS_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L)
    val MAX_RETRIES: Int get() = DELAYS_MS.size

    /** Delay before retry number [retryIndex] (0-based), or null when out of retries. */
    fun delayBefore(retryIndex: Int): Long? = DELAYS_MS.getOrNull(retryIndex)

    fun shouldRetry(outcome: SendOutcome, retriesDone: Int): Boolean =
        outcome == SendOutcome.NOT_SENT && retriesDone < MAX_RETRIES
}

sealed interface SendResult {
    val requestId: String
    val attempts: Int

    data class Delivered(override val requestId: String, override val attempts: Int, val outcome: SendOutcome) : SendResult
    data class GaveUp(override val requestId: String, override val attempts: Int) : SendResult
    /** Another path (outbox flush on reconnect) already delivered this request id. */
    data class AlreadyDone(override val requestId: String, override val attempts: Int) : SendResult
}

/**
 * Runs one send with the [SendRetryPolicy] schedule. [attempt] always sends the same
 * frame with the same [requestId]; it goes through PhoneUplink, so each retry uses
 * whichever server is active after a failover. [stillPending] lets the caller stop
 * early when the outbox flush already sent the frame.
 */
class SendRetrier(
    private val sleep: suspend (Long) -> Unit,
) {
    suspend fun run(
        requestId: String,
        attempt: suspend () -> SendOutcome,
        stillPending: () -> Boolean = { true },
    ): SendResult {
        var attempts = 0
        var retries = 0
        while (true) {
            if (!stillPending()) return SendResult.AlreadyDone(requestId, attempts)
            val outcome = attempt()
            attempts += 1
            if (outcome != SendOutcome.NOT_SENT) return SendResult.Delivered(requestId, attempts, outcome)
            if (!SendRetryPolicy.shouldRetry(outcome, retries)) return SendResult.GaveUp(requestId, attempts)
            sleep(SendRetryPolicy.delayBefore(retries)!!)
            retries += 1
        }
    }
}
