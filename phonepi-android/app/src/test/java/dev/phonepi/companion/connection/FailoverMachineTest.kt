package dev.phonepi.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverMachineTest {
    private val hp = ServerEntry("hp", "HP", "hp1.tailcbcc46.ts.net", 443, "t", primary = true)
    private val box = ServerEntry("box", "Box", "grokbot-box.tailcbcc46.ts.net", 443, "t")

    private fun machine(auto: Boolean = true, pinned: String? = null) =
        FailoverMachine(listOf(box, hp), auto, pinned)

    @Test
    fun startsOnPrimaryEvenWhenListedSecond() {
        val m = machine()
        assertEquals("hp", m.start(0)?.id)
        assertTrue(m.onPrimary)
    }

    @Test
    fun threeFastFailuresMoveToFallbackImmediately() {
        val m = machine()
        m.start(0)
        assertEquals(FailoverDecision.Stay, m.onFailure(1_000))
        assertEquals(FailoverDecision.Stay, m.onFailure(3_000))
        val d = m.onFailure(6_000) as FailoverDecision.SwitchTo
        assertEquals("box", d.server.id)
        assertEquals("hp", d.from?.id)
        assertTrue(d.immediate)
        assertEquals("box", m.activeId)
    }

    @Test
    fun deadlineFifteenSecondsAfterStartMovesEvenWhileAttemptHangs() {
        val m = machine()
        m.start(1_000)
        assertEquals(16_000L, m.deadlineAt())
        assertEquals(FailoverDecision.Stay, m.onDeadline(15_999))
        val d = m.onDeadline(16_000) as FailoverDecision.SwitchTo
        assertEquals("box", d.server.id)
        assertTrue(d.immediate)
    }

    @Test
    fun singleSlowFailurePastWindowMoves() {
        val m = machine()
        m.start(0)
        val d = m.onFailure(15_000)
        assertTrue(d is FailoverDecision.SwitchTo)
    }

    @Test
    fun linkLossStartsFreshFifteenSecondClock() {
        val m = machine()
        m.start(0)
        m.onConnected(500)
        assertNull(m.deadlineAt())
        m.onLinkLost(100_000)
        assertEquals(115_000L, m.deadlineAt())
        assertEquals(FailoverDecision.Stay, m.onFailure(101_000))
    }

    @Test
    fun bothDownAlternatesByFailureCountWithoutDeadlineAfterFirstHop() {
        val m = machine()
        m.start(0)
        m.onDeadline(15_000)
        assertEquals("box", m.activeId)
        assertNull(m.deadlineAt())
        assertEquals(FailoverDecision.Stay, m.onFailure(16_000))
        assertEquals(FailoverDecision.Stay, m.onFailure(60_000))
        val back = m.onFailure(200_000) as FailoverDecision.SwitchTo
        assertEquals("hp", back.server.id)
        assertFalse("later hops keep the backoff", back.immediate)
    }

    @Test
    fun reprobesPrimaryEveryThreeMinutesAndMovesBackWhenHealthy() {
        val m = machine()
        m.start(0)
        m.onDeadline(15_000)
        m.onConnected(16_000)
        assertFalse(m.shouldProbePrimary(16_000 + 179_999))
        assertTrue(m.shouldProbePrimary(16_000 + 180_000))
        assertEquals(FailoverDecision.Stay, m.onProbeResult(false, 196_000))
        assertFalse(m.shouldProbePrimary(196_000 + 60_000))
        assertTrue(m.shouldProbePrimary(196_000 + 180_000))
        val d = m.onProbeResult(true, 376_000) as FailoverDecision.SwitchTo
        assertEquals("hp", d.server.id)
        assertEquals("box", d.from?.id)
        assertTrue(d.immediate)
        assertTrue(m.onPrimary)
        assertFalse(m.connected)
    }

    @Test
    fun neverProbesWhileOnPrimary() {
        val m = machine()
        m.start(0)
        m.onConnected(1)
        assertFalse(m.shouldProbePrimary(10_000_000))
        assertEquals(FailoverDecision.Stay, m.onProbeResult(true, 10_000_000))
    }

    @Test
    fun autoOffStaysOnPrimaryForever() {
        val m = machine(auto = false)
        m.start(0)
        repeat(10) { assertEquals(FailoverDecision.Stay, m.onFailure(100_000L * (it + 1))) }
        assertNull(m.deadlineAt())
        assertEquals("hp", m.activeId)
    }

    @Test
    fun manualPinStartsThereAndDisablesFailoverAndProbe() {
        val m = machine(pinned = "box")
        assertEquals("box", m.start(0)?.id)
        assertFalse(m.failoverEnabled)
        repeat(5) { assertEquals(FailoverDecision.Stay, m.onFailure(20_000L * (it + 1))) }
        m.onConnected(200_000)
        assertFalse(m.shouldProbePrimary(10_000_000))
    }

    @Test
    fun pinThenUnpinRestoresFailover() {
        val m = machine()
        m.start(0)
        assertEquals("box", m.pin("box", 10)?.id)
        assertFalse(m.failoverEnabled)
        m.unpin()
        assertTrue(m.failoverEnabled)
    }

    @Test
    fun turningAutoOffMidOutageCancelsDeadline() {
        val m = machine()
        m.start(0)
        m.setAuto(false)
        assertNull(m.deadlineAt())
        assertEquals(FailoverDecision.Stay, m.onDeadline(20_000))
    }

    @Test
    fun singleServerNeverSwitches() {
        val m = FailoverMachine(listOf(hp))
        m.start(0)
        assertFalse(m.failoverEnabled)
        assertEquals(FailoverDecision.Stay, m.onFailure(100_000))
    }

    @Test
    fun connectedResetsHopCountSoNextOutageGetsDeadlineAgain() {
        val m = machine()
        m.start(0)
        m.onDeadline(15_000)
        m.onConnected(16_000)
        m.onLinkLost(50_000)
        assertEquals(65_000L, m.deadlineAt())
        val d = m.onDeadline(65_000) as FailoverDecision.SwitchTo
        assertEquals("hp", d.server.id)
    }
}
