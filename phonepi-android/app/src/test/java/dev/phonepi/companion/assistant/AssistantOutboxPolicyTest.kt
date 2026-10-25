package dev.phonepi.companion.assistant

import dev.phonepi.companion.textsend.PhoneUplink
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

class AssistantOutboxRetryTest {
    @Test
    fun retryableAckSchedulesOneDelayedRetryAndDoesNotTightLoop() {
        val store = MemoryOutboxStore()
        val scheduler = RecordingOutboxScheduler()
        AssistantOutbox.resetForTest(store, scheduler)
        AssistantOutbox.nowMs = { 0L }
        AssistantOutbox.jitterMs = { 0 }
        val sent = mutableListOf<String>()
        PhoneUplink.send = { line ->
            sent += line
            true
        }
        try {
            val json = """{"id":"prompt-retry1","type":"inbound_prompt"}"""
            AssistantOutbox.enqueueLine(json)
            assertEquals(1, sent.size)

            AssistantOutbox.ackId(
                id = "prompt-retry1",
                ok = false,
                retryable = true,
                retryAfterSec = 60,
            )
            assertEquals(1, sent.size)
            val pending = scheduler.pending()
            assertEquals(1, pending.size)
            assertEquals("retry:prompt-retry1", pending[0].token)
            assertEquals(60_000L, pending[0].delayMs)

            scheduler.runDue(0L)
            assertEquals(1, sent.size)

            scheduler.runDue(60_000L)
            assertEquals(2, sent.size)
            assertEquals(sent[0], sent[1])
            assertTrue(scheduler.pending().none { it.delayMs == 0L })
            assertEquals(2, sent.size)
        } finally {
            PhoneUplink.send = null
            AssistantOutbox.resetForTest()
        }
    }
}
