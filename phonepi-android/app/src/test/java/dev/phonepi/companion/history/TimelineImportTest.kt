package dev.phonepi.companion.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineImportTest {
    @Test
    fun parsesCurrentExportAndLegacyTakeout() {
        val text = """
            {
              "semanticSegments": [
                {
                  "startTime": "2026-10-13T12:10:00-07:00",
                  "endTime": "2026-10-13T13:40:00-07:00",
                  "visit": {
                    "topCandidate": {
                      "semanticType": "FOOD_AND_DRINK",
                      "placeLocation": { "latLng": "33.20010°, -117.24020°" }
                    }
                  }
                },
                {
                  "startTime": "2026-10-13T13:40:00-07:00",
                  "endTime": "2026-10-13T13:55:00-07:00",
                  "activity": {
                    "start": { "latLng": "33.20010°, -117.24020°" },
                    "end": { "latLng": "33.23000°, -117.28000°" },
                    "distanceMeters": 4200,
                    "activityType": "IN_PASSENGER_VEHICLE"
                  }
                }
              ],
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": {
                      "latitudeE7": 332100000,
                      "longitudeE7": -1172500000,
                      "name": "Pizza Place",
                      "address": "100 Main St, Vista, CA 92084"
                    },
                    "duration": {
                      "startTimestamp": "2026-10-11T18:00:00Z",
                      "endTimestamp": "2026-10-11T19:15:00Z"
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val result = parseTimelineExport(text)
        assertNull(result.error)
        assertEquals(2, result.visits)
        assertEquals(1, result.drives)
        val pizza = result.events.single { it.placeName == "Pizza Place" }
        assertEquals("100 Main St, Vista, CA 92084", pizza.address)
        assertEquals("timeline", pizza.source)
        assertTrue(pizza.id.startsWith("tl-visit-"))
        val drive = result.events.single { it.kind == "drive" }
        assertEquals(4200.0, drive.distanceMeters!!, 0.1)
        val again = parseTimelineExport(text)
        assertEquals(result.events.map { it.id }.toSet(), again.events.map { it.id }.toSet())
    }

    @Test
    fun rawPointsAreRejected() {
        val result = parseTimelineExport("""{"locations":[{"latitudeE7":1,"longitudeE7":2}]}""")
        assertTrue(result.error!!.contains("raw location points"))
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun syncPayloadHasNoSecretField() {
        val event = LocationEventRecord(
            id = "visit-00000001",
            kind = "visit",
            startMs = 10,
            endMs = 20,
            latitude = 1.0,
            longitude = 2.0,
            endLatitude = null,
            endLongitude = null,
            placeName = "Cafe",
            address = "1 Road",
            distanceMeters = null,
            source = "phonepi",
            updatedAtMs = 20,
        )
        val text = locationSyncMessage(listOf(event))
        assertTrue(text.contains("\"type\":\"location_sync\""))
        assertTrue(!text.contains("token"))
        val ack = parseSyncAck(
            """{"type":"location_sync_ack","accepted":["visit-00000001"],"rejected":[{"id":"x","error":"bad"}]}""",
        )
        assertEquals(listOf("visit-00000001"), ack!!.accepted)
        assertEquals(listOf("x"), ack.rejected)
    }
}
