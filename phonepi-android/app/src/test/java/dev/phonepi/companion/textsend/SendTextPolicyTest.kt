package dev.phonepi.companion.textsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendTextPolicyTest {
    private val mom = ContactNumber("1", "Mom", "+1 (555) 123-4567")
    private val momWork = ContactNumber("1", "Mom", "5559876543")
    private val otherMom = ContactNumber("2", "Mom Smith", "5550001111")

    @Test
    fun attributedAlwaysStartsWithThePrefixAndAsAlexDoesNotAddOne() {
        val prefixed = SendTextPolicy.carrierText("attributed", "Running late")
        assertTrue(prefixed.startsWith(SendTextPolicy.ATTRIBUTION_PREFIX))
        assertEquals(
            "[Alex's assistant Grok]: Running late",
            prefixed,
        )
        assertEquals(
            prefixed,
            SendTextPolicy.carrierText("attributed", prefixed),
        )
        assertEquals(
            "On my way",
            SendTextPolicy.carrierText("as_alex", "On my way"),
        )
        assertEquals(
            SendTextPolicy.ATTRIBUTION_PREFIX + "I typed this",
            SendTextPolicy.carrierText("as_alex", SendTextPolicy.ATTRIBUTION_PREFIX + "I typed this"),
        )
    }

    @Test
    fun asAlexNeverSendsWithoutTapOrSpokenSend() {
        val record = pending("as_alex", "On my way")
        val blocked = SendTextPolicy.decide(
            record,
            SendAction.Attributed(SendTextPolicy.carrierText("attributed", record.message)),
            now = 1_000,
        )
        assertTrue(blocked is SendDecision.Keep)
        val unclear = SendTextPolicy.decide(record, SendAction.Confirm("maybe"), now = 1_000)
        assertTrue(unclear is SendDecision.Keep)
        val tap = SendTextPolicy.decide(record, SendAction.Confirm("tap"), now = 1_000)
        assertTrue(tap is SendDecision.Send)
        assertEquals("On my way", (tap as SendDecision.Send).carrierText)
        assertEquals("tap", tap.confirmMethod)
        val spoken = SendTextPolicy.decide(record, SendAction.Confirm("spoken"), now = 2_000)
        assertEquals("spoken", (spoken as SendDecision.Send).confirmMethod)
    }

    @Test
    fun cancelAndExpiryAreFinal() {
        val record = pending("as_alex", "Nope")
        val cancelled = SendTextPolicy.decide(record, SendAction.Cancel, now = 1_000)
        assertEquals("cancelled", (cancelled as SendDecision.Close).status)
        val closed = record.copy(status = "cancelled")
        val retry = SendTextPolicy.decide(closed, SendAction.Confirm("tap"), now = 2_000)
        assertTrue(retry is SendDecision.Keep)
        assertFalse(retry is SendDecision.Send)

        val expired = SendTextPolicy.decide(
            record,
            SendAction.Confirm("tap"),
            now = record.expiresAt,
        )
        assertEquals("expired", (expired as SendDecision.Close).status)
        val stuck = record.copy(status = "expired")
        val later = SendTextPolicy.decide(stuck, SendAction.Confirm("spoken"), now = record.expiresAt + 1)
        assertTrue(later !is SendDecision.Send)
    }

    @Test
    fun attributedSendRequiresThePrefix() {
        val record = pending("attributed", "Traffic is slow")
        val body = SendTextPolicy.carrierText("attributed", record.message)
        val missing = SendTextPolicy.decide(record, SendAction.Attributed(record.message), now = 1_000)
        assertEquals("failed", (missing as SendDecision.Close).status)
        val sent = SendTextPolicy.decide(record, SendAction.Attributed(body), now = 1_000)
        assertTrue(sent is SendDecision.Send)
        assertTrue((sent as SendDecision.Send).carrierText.startsWith(SendTextPolicy.ATTRIBUTION_PREFIX))
        assertEquals("attributed", sent.confirmMethod)
    }

    @Test
    fun oneContactResolvesAndAmbiguousOrEmptyDoesNot() {
        val one = SendTextPolicy.resolveRecipient("mom", listOf(mom))
        assertTrue(one is RecipientResolution.One)
        assertEquals("+15551234567", (one as RecipientResolution.One).number)
        assertEquals("Mom", one.name)

        val many = SendTextPolicy.resolveRecipient("Mom", listOf(mom, momWork))
        assertTrue(many is RecipientResolution.Clarify)
        assertEquals(2, (many as RecipientResolution.Clarify).candidates.size)
        assertTrue(many.candidates.all { !it.numberMasked.contains("5551234567") })

        val momJones = ContactNumber("3", "Mom Jones", "5552223333")
        val twoPeople = SendTextPolicy.resolveRecipient("Mom", listOf(otherMom, momJones))
        assertTrue(twoPeople is RecipientResolution.Clarify)
        assertEquals(2, (twoPeople as RecipientResolution.Clarify).candidates.size)

        val none = SendTextPolicy.resolveRecipient("Nobody", listOf(mom))
        assertTrue(none is RecipientResolution.Clarify)
        assertTrue((none as RecipientResolution.Clarify).candidates.isEmpty())

        val group = SendTextPolicy.resolveRecipient("Mom and Dad", listOf(mom))
        assertTrue(group is RecipientResolution.Rejected)

        val direct = SendTextPolicy.resolveRecipient("+1 555 123 4567", listOf(mom))
        assertTrue(direct is RecipientResolution.One)
        assertEquals("Mom", (direct as RecipientResolution.One).name)
    }

    @Test
    fun spokenSendIsStrictAndDrivingIsAnySignal() {
        assertEquals(SpokenChoice.SEND, SendTextPolicy.interpretSpoken("Send"))
        assertEquals(SpokenChoice.SEND, SendTextPolicy.interpretSpoken("please send."))
        assertEquals(SpokenChoice.CANCEL, SendTextPolicy.interpretSpoken("cancel"))
        assertEquals(SpokenChoice.UNCLEAR, SendTextPolicy.interpretSpoken(""))
        assertEquals(SpokenChoice.UNCLEAR, SendTextPolicy.interpretSpoken("send it to Dad instead"))
        assertEquals(SpokenChoice.UNCLEAR, SendTextPolicy.interpretSpoken("no"))
        assertTrue(SendTextPolicy.drivingDetected(false, false, false, true))
        assertTrue(SendTextPolicy.drivingDetected(true, false, false, false))
        assertFalse(SendTextPolicy.drivingDetected(false, false, false, false))
    }

    @Test
    fun budgetsAreSeparateForEachMode() {
        var now = 10_000L
        val budget = SendTextBudget(now = { now })
        repeat(5) { assertTrue(budget.allow("as_alex") is SendTextBudget.BudgetResult.Allowed) }
        assertTrue(budget.allow("as_alex") is SendTextBudget.BudgetResult.Denied)
        assertTrue(budget.allow("attributed") is SendTextBudget.BudgetResult.Allowed)
        now += 60_000L
        repeat(25) {
            now += 60_000L
            assertTrue(budget.allow("as_alex") is SendTextBudget.BudgetResult.Allowed)
        }
        val day = budget.allow("as_alex")
        assertTrue(day is SendTextBudget.BudgetResult.Denied)
        assertEquals("day", (day as SendTextBudget.BudgetResult.Denied).scope)
    }

    private fun pending(mode: String, message: String) = SendRecord(
        id = "send-test-1",
        mode = mode,
        recipientName = "Mom",
        number = "+15551234567",
        message = message,
        createdAt = 0,
        expiresAt = SendTextPolicy.TTL_MS,
    )
}
