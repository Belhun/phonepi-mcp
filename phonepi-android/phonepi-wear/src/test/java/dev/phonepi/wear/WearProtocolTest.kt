package dev.phonepi.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WearProtocolTest {
    @Test
    fun promptPayloadHasNoToken() {
        val payload = String(WearProtocol.encodePrompt("hello", "ask"), Charsets.UTF_8)
        assertTrue(payload.contains("hello"))
        assertFalse(payload.contains("token"))
        assertFalse(payload.contains("TS_AUTHKEY"))
    }

    @Test
    fun quietEncodesMinutes() {
        val payload = String(WearProtocol.encodeQuiet(60), Charsets.UTF_8)
        assertTrue(payload.contains("60"))
        assertFalse(payload.contains("token"))
    }

    @Test
    fun ackRoundTrip() {
        val ack = WearProtocol.decodeAck("""{"ok":true,"action":"quiet","message":"quiet 60m"}""")
        assertTrue(ack.ok)
        assertEquals("quiet", ack.action)
        assertEquals("quiet 60m", ack.message)
    }

    @Test
    fun pathsStayOnPhonePiPrefix() {
        assertTrue(WearPaths.PROMPT.startsWith("/phonepi/"))
        assertTrue(WearPaths.QUIET.startsWith("/phonepi/"))
        assertTrue(WearPaths.IM_OK.startsWith("/phonepi/"))
        assertTrue(WearPaths.NEXT_ALARM.startsWith("/phonepi/"))
        assertEquals("phonepi_phone", WearPaths.PHONE_CAPABILITY)
    }

    @Test
    fun nextAlarmFormatMatchesPhone() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val today = ZonedDateTime.of(2026, 10, 25, 18, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("6:05 PM", NextAlarmFormat.shortText(today, now, zone))
        assertEquals("No alarm", NextAlarmFormat.shortText(null, now, zone))
    }
}
