package dev.phonepi.companion.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGateTest {
    @Test
    fun allowlistIsExactlyTheFourPhoneCommands() {
        assertEquals(
            setOf(
                "phone_add_location",
                "phone_list_locations",
                "phone_navigate",
                "phone_remove_location",
            ),
            PhoneCommands.ALL.map { it.name }.toSet(),
        )
        assertFalse(PhoneCommands.isAllowlisted("make_call"))
        assertFalse(PhoneCommands.isAllowlisted("am"))
        assertFalse(PhoneCommands.isAllowlisted("shell"))
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
