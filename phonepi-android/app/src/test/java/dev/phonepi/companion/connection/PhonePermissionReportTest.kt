package dev.phonepi.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePermissionReportTest {
    @Test
    fun missingListIsTheFalseAllowlistedFlags() {
        val snapshot = PhonePermissionReport.fromFlags(
            mapOf(
                "overlay" to true,
                "post_notifications" to true,
                "full_screen_intent" to false,
                "notification_listener" to false,
                "dnd_access" to true,
                "exact_alarms" to true,
                "token" to true,
            ),
        )
        assertEquals(
            listOf("full_screen_intent", "notification_listener"),
            snapshot.missing,
        )
        assertFalse(snapshot.granted.containsKey("token"))
        val json = PhonePermissionReport.toJson(snapshot)
        assertTrue(json.contains("\"type\":\"permissions\""))
        assertTrue(json.contains("\"overlay\":true"))
        assertTrue(json.contains("\"full_screen_intent\":false"))
        assertFalse(json.contains("token"))
    }

    @Test
    fun absentFlagsCountAsMissing() {
        val snapshot = PhonePermissionReport.fromFlags(emptyMap())
        assertEquals(PhonePermissionReport.KEYS, snapshot.missing)
    }
}
