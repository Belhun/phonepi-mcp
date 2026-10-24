package dev.phonepi.clock.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestLedgerTest {
    @Test
    fun repeatReturnsTheFirstBodyAndIgnoresASecondWrite() {
        val ledger = RequestLedger()
        assertNull(ledger.replay("req-1", 0))
        ledger.remember("req-1", 1_000, """{"status":"success","id":"a"}""")
        ledger.remember("req-1", 2_000, """{"status":"success","id":"b"}""")
        assertEquals("""{"status":"success","id":"a"}""", ledger.replay("req-1", 3_000))
    }

    @Test
    fun entriesExpireAfterSevenDays() {
        val ledger = RequestLedger()
        ledger.remember("old", 0, "first")
        val stillThere = RequestLedger.SEVEN_DAYS_MS
        assertEquals("first", ledger.replay("old", stillThere))
        assertNull(ledger.replay("old", stillThere + 1))
    }

    @Test
    fun capDropsTheOldestEntry() {
        val ledger = RequestLedger(maxEntries = 2)
        ledger.remember("a", 1, "A")
        ledger.remember("b", 2, "B")
        ledger.remember("c", 3, "C")
        assertNull(ledger.replay("a", 4))
        assertEquals("B", ledger.replay("b", 4))
        assertEquals("C", ledger.replay("c", 4))
        assertEquals(2, ledger.size())
    }

    @Test
    fun restoreKeepsOrderAndReplay() {
        val ledger = RequestLedger()
        ledger.restore(
            listOf(
                RequestLedger.Entry("b", 2, "B"),
                RequestLedger.Entry("a", 1, "A"),
            )
        )
        assertEquals(listOf("a", "b"), ledger.snapshot().map { it.requestId })
        assertEquals("A", ledger.replay("a", 3))
    }
}
