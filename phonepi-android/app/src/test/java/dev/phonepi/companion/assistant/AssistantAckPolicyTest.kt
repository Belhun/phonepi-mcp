package dev.phonepi.companion.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAckPolicyTest {
    @Test
    fun dropsOnSuccessOrPermanentRejectAndKeepsRetryableFailures() {
        assertTrue(AssistantAckPolicy.shouldDrop(ok = true, retryable = false))
        assertTrue(AssistantAckPolicy.shouldDrop(ok = true, retryable = true))
        assertTrue(AssistantAckPolicy.shouldDrop(ok = false, retryable = false))
        assertFalse(AssistantAckPolicy.shouldDrop(ok = false, retryable = true))
    }
}
