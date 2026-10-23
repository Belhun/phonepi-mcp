package dev.phonepi.companion.textsend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendRetryTest {
    private fun run(
        outcomes: List<SendOutcome>,
        pending: () -> Boolean = { true },
    ): Triple<SendResult, List<Long>, Int> {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val result = runBlocking {
            SendRetrier { sleeps += it }.run(
                requestId = "req-1",
                attempt = { outcomes[calls++] },
                stillPending = pending,
            )
        }
        return Triple(result, sleeps, calls)
    }

    @Test
    fun scheduleIsTwoFiveTenSeconds() {
        assertEquals(listOf(2_000L, 5_000L, 10_000L), SendRetryPolicy.DELAYS_MS)
        assertEquals(2_000L, SendRetryPolicy.delayBefore(0))
        assertEquals(5_000L, SendRetryPolicy.delayBefore(1))
        assertEquals(10_000L, SendRetryPolicy.delayBefore(2))
        assertEquals(null, SendRetryPolicy.delayBefore(3))
    }

    @Test
    fun givesUpAfterThreeRetriesWithExactWaits() {
        val (result, sleeps, calls) = run(List(4) { SendOutcome.NOT_SENT })
        assertTrue(result is SendResult.GaveUp)
        assertEquals(4, result.attempts)
        assertEquals(listOf(2_000L, 5_000L, 10_000L), sleeps)
        assertEquals(4, calls)
    }

    @Test
    fun succeedsOnSecondRetry() {
        val (result, sleeps, _) = run(
            listOf(SendOutcome.NOT_SENT, SendOutcome.NOT_SENT, SendOutcome.HANDED_OFF),
        )
        assertTrue(result is SendResult.Delivered)
        assertEquals(3, result.attempts)
        assertEquals(listOf(2_000L, 5_000L), sleeps)
    }

    @Test
    fun neverRetriesAfterAck() {
        val (result, sleeps, calls) = run(listOf(SendOutcome.ACKED, SendOutcome.NOT_SENT))
        assertTrue(result is SendResult.Delivered)
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
        assertFalse(SendRetryPolicy.shouldRetry(SendOutcome.ACKED, 0))
    }

    @Test
    fun neverRetriesOnceTheSocketTookTheFrame() {
        val (_, sleeps, calls) = run(listOf(SendOutcome.HANDED_OFF, SendOutcome.NOT_SENT))
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
        assertFalse(SendRetryPolicy.shouldRetry(SendOutcome.HANDED_OFF, 0))
    }

    @Test
    fun stopsWhenFlushAlreadySentIt() {
        var checks = 0
        val (result, _, calls) = run(List(4) { SendOutcome.NOT_SENT }) { checks++ < 1 }
        assertTrue(result is SendResult.AlreadyDone)
        assertEquals(1, calls)
    }

    @Test
    fun retriesOnlyPreAckFailures() {
        assertTrue(SendRetryPolicy.shouldRetry(SendOutcome.NOT_SENT, 0))
        assertTrue(SendRetryPolicy.shouldRetry(SendOutcome.NOT_SENT, 2))
        assertFalse(SendRetryPolicy.shouldRetry(SendOutcome.NOT_SENT, 3))
    }
}
