package dev.phonepi.companion.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGateTest {
    @Test
    fun allowlistIsExactlyTheSixPhoneCommands() {
        assertEquals(
            setOf(
                "phone_add_location",
                "phone_list_locations",
                "phone_navigate",
                "phone_push_update",
                "phone_remove_location",
                "phone_set_volume",
            ),
            PhoneCommands.ALL.map { it.name }.toSet(),
        )
        assertFalse(PhoneCommands.isAllowlisted("make_call"))
        assertFalse(PhoneCommands.isAllowlisted("am"))
        assertFalse(PhoneCommands.isAllowlisted("shell"))
    }

    @Test
    fun notificationReadsAreRateLimitedAndNotPhoneCommands() {
        assertFalse(PhoneCommands.isAllowlisted("get_notifications"))
        assertFalse(PhoneCommands.isAllowlisted("list_notification_apps"))
        assertFalse(PhoneCommands.isAllowlisted("get_volumes"))
        assertEquals(60, PhoneReadTools.maxPerMinute("get_notifications"))
        assertEquals(60, PhoneReadTools.maxPerMinute("list_notification_apps"))
        assertEquals(60, PhoneReadTools.maxPerMinute("get_volumes"))
        var now = 10_000L
        val limiter = CommandRateLimiter(windowMs = 60_000L, now = { now })
        repeat(60) {
            assertTrue(limiter.allow("get_notifications", 60) is CommandRateLimiter.Result.Allowed)
        }
        assertTrue(limiter.allow("get_notifications", 60) is CommandRateLimiter.Result.Denied)
    }

    @Test
    fun rateLimiterBlocksNavigateAfterBudget() {
        var now = 10_000L
        val limiter = CommandRateLimiter(windowMs = 60_000L, now = { now })
        assertTrue(limiter.allow("phone_navigate", 2) is CommandRateLimiter.Result.Allowed)
        assertTrue(limiter.allow("phone_navigate", 2) is CommandRateLimiter.Result.Allowed)
        val denied = limiter.allow("phone_navigate", 2)
        assertTrue(denied is CommandRateLimiter.Result.Denied)
        now += 60_000L
        assertTrue(limiter.allow("phone_navigate", 2) is CommandRateLimiter.Result.Allowed)
    }
}
