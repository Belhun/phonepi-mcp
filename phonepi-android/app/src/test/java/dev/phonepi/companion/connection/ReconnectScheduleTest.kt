package dev.phonepi.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectScheduleTest {
    @Test
    fun neverGivesUpAndStaysInsideTheCapAndJitter() {
        val attempts = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 50, 1_000)
        for (attempt in attempts) {
            assertFalse(ReconnectSchedule.givesUp(attempt))
            val low = ReconnectSchedule.delayMs(attempt, 0.0)
            val mid = ReconnectSchedule.delayMs(attempt, 0.5)
            val high = ReconnectSchedule.delayMs(attempt, 1.0)
            assertTrue("low $low for attempt $attempt", low in 1..ReconnectSchedule.CAP_MS)
            assertTrue(mid in low..high)
            assertTrue(high <= ReconnectSchedule.CAP_MS)
            val uncapped = minOf(
                ReconnectSchedule.CAP_MS,
                ReconnectSchedule.BASE_MS shl attempt.coerceAtMost(20),
            )
            val floor = (uncapped * 0.8).toLong()
            assertTrue("low $low floor $floor attempt $attempt", low >= floor - 1)
            if (uncapped >= ReconnectSchedule.CAP_MS) {
                assertEquals(ReconnectSchedule.CAP_MS, high)
            }
        }
    }

    @Test
    fun firstDelaysGrowUntilTheFiveMinuteCap() {
        assertEquals(1_000L, ReconnectSchedule.delayMs(0, 0.5))
        assertEquals(2_000L, ReconnectSchedule.delayMs(1, 0.5))
        assertEquals(4_000L, ReconnectSchedule.delayMs(2, 0.5))
        assertEquals(32_000L, ReconnectSchedule.delayMs(5, 0.5))
        assertEquals(256_000L, ReconnectSchedule.delayMs(8, 0.5))
        assertEquals(ReconnectSchedule.CAP_MS, ReconnectSchedule.delayMs(9, 0.5))
        assertEquals(ReconnectSchedule.CAP_MS, ReconnectSchedule.delayMs(40, 0.5))
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        val mid = ReconnectSchedule.delayMs(3, 0.5)
        val low = ReconnectSchedule.delayMs(3, 0.0)
        val high = ReconnectSchedule.delayMs(3, 1.0)
        assertEquals(8_000L, mid)
        assertEquals(6_400L, low)
        assertEquals(9_600L, high)
    }
}
