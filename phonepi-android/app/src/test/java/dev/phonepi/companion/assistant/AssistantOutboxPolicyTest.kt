package dev.phonepi.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantOutboxPolicyTest {
    @Test
    fun dropsOnSuccessOrPermanentRejectAndKeepsRetryableFailures() {
        assertTrue(AssistantOutboxPolicy.shouldDrop(ok = true, retryable = false))
        assertTrue(AssistantOutboxPolicy.shouldDrop(ok = true, retryable = true))
        assertTrue(AssistantOutboxPolicy.shouldDrop(ok = false, retryable = false))
        assertFalse(AssistantOutboxPolicy.shouldDrop(ok = false, retryable = true))
        assertFalse(AssistantOutboxPolicy.shouldFlushOnAck(ok = false, retryable = true))
        assertFalse(AssistantOutboxPolicy.shouldFlushOnAck(ok = false, retryable = false))
    }

    @Test
    fun retryDelayHonorsRetryAfterJitterAndMaxBackoff() {
        assertEquals(60_000L, AssistantOutboxPolicy.retryDelayMs(60, 0, 0))
        assertEquals(120_000L, AssistantOutboxPolicy.retryDelayMs(60, 1, 0))
        assertEquals(61_500L, AssistantOutboxPolicy.retryDelayMs(60, 0, 1_500))
        assertEquals(
            AssistantOutboxPolicy.MAX_BACKOFF_MS,
            AssistantOutboxPolicy.retryDelayMs(60, 8, 0),
        )
    }

    @Test
    fun fortyQueuedPromptsConvergeWithBoundedSends() {
        val queued = (0 until 40).map { """{"id":"prompt-$it","type":"inbound_prompt"}""" }.toMutableList()
        val accepted = mutableSetOf<String>()
        var sends = 0
        var now = 0L
        val sentAt = ArrayDeque<Long>()

        fun prune() {
            while (sentAt.isNotEmpty() && now - sentAt.first() >= AssistantOutboxPolicy.WINDOW_MS) {
                sentAt.removeFirst()
            }
        }

        fun idOf(line: String) = Regex(""""id":"([^"]+)"""").find(line)!!.groupValues[1]

        fun flush() {
            prune()
            val batch = AssistantOutboxPolicy.takeFlushBatch(queued, sentAt.size)
            batch.forEach {
                sends += 1
                sentAt.addLast(now)
                val id = idOf(it)
                if (accepted.size < 30) {
                    accepted += id
                    queued.removeAll { row -> idOf(row) == id }
                }
            }
        }

        flush()
        assertEquals(20, sends)
        assertEquals(20, accepted.size)
        assertEquals(20, queued.size)

        now += AssistantOutboxPolicy.WINDOW_MS
        flush()
        assertEquals(40, sends)
        assertEquals(30, accepted.size)
        assertEquals(10, queued.size)

        val retryable = queued.toList()
        retryable.forEach { line ->
            val delay = AssistantOutboxPolicy.retryDelayMs(60, 0, 0)
            assertEquals(60_000L, delay)
            assertFalse(AssistantOutboxPolicy.shouldFlushOnAck(ok = false, retryable = true))
            sends += 1
            accepted += idOf(line)
            queued.remove(line)
        }
        now += AssistantOutboxPolicy.WINDOW_MS
        assertEquals(40, accepted.size)
        assertTrue(queued.isEmpty())
        assertTrue("sends stayed bounded, was $sends", sends <= 50)
    }
}
