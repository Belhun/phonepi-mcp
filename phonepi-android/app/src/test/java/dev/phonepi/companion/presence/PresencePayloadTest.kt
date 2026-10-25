package dev.phonepi.companion.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresencePayloadTest {
    @Test
    fun syncFrameCarriesTypeAndPlaceWithoutSecretFields() {
        val snapshot = PresenceSnapshot(
            capturedAtMs = 1_700_000_000_000L,
            enabled = true,
            paused = false,
            place = "Home",
            minutesAtPlace = 12,
            arrivedAtMs = 1_700_000_000_000L,
            driving = false,
            moving = false,
            activity = "still",
            dnd = "off",
            latitude = 33.38,
            longitude = -117.25,
            accuracyMeters = 15.0,
            lastEvent = "arrive",
            lastEventPlace = "Home",
            lastEventMs = 1_700_000_000_000L,
            source = "geofence",
        )
        val text = presenceSyncMessage(snapshot)
        assertTrue(text.contains("\"type\":\"presence_sync\""))
        assertTrue(text.contains("\"place\":\"Home\""))
        assertFalse(text.contains("token"))
        assertFalse(text.contains("wstoken"))
        assertEquals("""{"type":"presence_cleared"}""", presenceClearedMessage())
        assertTrue(parsePresenceSyncAck("""{"type":"presence_sync_ack","ok":true}"""))
        assertTrue(parsePresenceClearedAck("""{"type":"presence_cleared_ack"}"""))
        assertFalse(parsePresenceSyncAck("""{"type":"location_sync_ack"}"""))
    }
}
