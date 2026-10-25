package dev.phonepi.companion.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class QuietHoldPolicyTest {
    @Test
    fun overflowBeyondTwentyIsSummarizedNotSilent() {
        assertEquals(0, QuietHoldPolicy.droppedCount(20))
        assertEquals(5, QuietHoldPolicy.droppedCount(25))
        assertEquals("20 cards were held", QuietHoldPolicy.summaryText(20, 0))
        assertEquals("20 cards were held · 5 more held", QuietHoldPolicy.summaryText(20, 5))
    }
}
