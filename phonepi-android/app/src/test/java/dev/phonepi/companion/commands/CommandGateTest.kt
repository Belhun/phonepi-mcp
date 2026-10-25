package dev.phonepi.companion.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGateTest {
    @Test
    fun allowlistIsExactlyThePhoneCommands() {
        assertEquals(
            setOf(
                "clear_location_history",
                "clear_presence_history",
                "clear_usage_history",
                "dismiss_alarm",
                "dismiss_timer",
                "get_photo",
                "pause_location_logging",
                "pause_presence_logging",
                "pause_usage_logging",
                "phone_add_location",
                "phone_list_locations",
                "phone_navigate",
                "phone_push_update",
                "phone_remove_location",
                "phone_set_volume",
                "get_send_status",
                "request_send_text",
                "set_alarm",
                "set_timer",
            ),
            PhoneCommands.ALL.map { it.name }.toSet(),
        )
        assertEquals(PhoneCommandSpec.Kind.READ, PhoneCommands.byName.getValue("get_photo").kind)
        assertEquals(12, PhoneCommands.byName.getValue("get_photo").maxPerMinute)
        assertFalse(PhoneCommands.isAllowlisted("get_recent_photos"))
        assertFalse(PhoneCommands.isAllowlisted("get_screen_time"))
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
        assertEquals(30, PhoneReadTools.maxPerMinute("get_recent_photos"))
        assertEquals(60, PhoneReadTools.maxPerMinute("list_alarms"))
        assertFalse(PhoneCommands.isAllowlisted("list_alarms"))
        assertEquals(10, PhoneCommands.byName.getValue("set_alarm").maxPerMinute)
        assertEquals(PhoneCommandSpec.Kind.ACTION, PhoneCommands.byName.getValue("set_timer").kind)
        assertFalse(PhoneCommands.isAllowlisted("get_recent_photos"))
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
