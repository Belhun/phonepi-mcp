package dev.phonepi.companion.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceLogicTest {
    private val home = PresencePlace("home", "Home", 33.3854, -117.2511)
    private val work = PresencePlace("work", "Work", 33.5000, -117.1500)
    private val church = PresencePlace("church", "Church", 33.3859, -117.2500)
    private val places = listOf(home, work, church)
    private val start = 1_700_000_000_000L

    @Test
    fun nearestPresencePlaceWinsAndAwayOutsideAll() {
        val atHome = PresenceLogic.matchPlace(33.3854, -117.2511, places)
        assertEquals("Home", atHome!!.name)
        val atWork = PresenceLogic.matchPlace(33.5000, -117.1500, places)
        assertEquals("Work", atWork!!.name)
        assertNull(PresenceLogic.matchPlace(32.7, -117.1, places))
    }

    @Test
    fun geofenceListCapsAt100AndDropsPlacesWithoutCoordinates() {
        val many = (1..120).map { i ->
            PresencePlace("p$i", "Place $i", 33.0 + i * 0.001, -117.0)
        }
        assertEquals(PRESENCE_MAX_GEOFENCES, PresenceLogic.geofencePlaces(many).size)
        val mixed = listOf(
            PresencePlace("ok", "Ok", 33.0, -117.0),
            PresencePlace("bad", "Bad", Double.NaN, -117.0),
        )
        assertEquals(listOf("ok"), PresenceLogic.geofencePlaces(mixed).map { it.nameKey })
    }

    @Test
    fun geofenceIdsStayUnderTheAndroidLimitAndAreStable() {
        val id = home.geofenceId()
        assertTrue(id.startsWith("p:"))
        assertTrue(id.length <= 100)
        assertEquals(id, PresencePlace("home", "Home", 1.0, 2.0).geofenceId())
    }

    @Test
    fun fixArrivesAndCountsMinutesThenLeaves() {
        var memory = PresenceMemory.empty()
        memory = PresenceLogic.onFix(memory, 33.3854, -117.2511, 12.0, start, places)
        assertEquals("Home", memory.placeName)
        assertEquals("arrive", memory.lastEvent)
        assertEquals(start, memory.arrivedAtMs)
        val later = start + 25 * 60_000L + 1_000L
        memory = PresenceLogic.onFix(memory, 33.38541, -117.25112, 12.0, later, places)
        assertEquals(25, PresenceLogic.minutesAt(memory.arrivedAtMs, later))
        memory = PresenceLogic.onFix(memory, 32.7, -117.1, 12.0, later + 60_000L, places)
        assertEquals(PRESENCE_AWAY, memory.placeName)
        assertEquals("leave", memory.lastEvent)
        assertEquals("Home", memory.lastEventPlace)
        assertNull(memory.arrivedAtMs)
    }

    @Test
    fun geofenceEnterAndExitUpdatePlace() {
        var memory = PresenceMemory.empty()
        memory = PresenceLogic.onGeofence(memory, listOf(work.geofenceId()), enter = true, nowMs = start, places = places)
        assertEquals("Work", memory.placeName)
        memory = PresenceLogic.onGeofence(memory, listOf(work.geofenceId()), enter = false, nowMs = start + 1_000L, places = places)
        assertEquals(PRESENCE_AWAY, memory.placeName)
    }

    @Test
    fun drivingAndWalkingFlagsComeFromActivity() {
        var memory = PresenceMemory.empty()
        memory = PresenceLogic.onActivity(memory, "driving", start)
        assertTrue(memory.driving)
        assertTrue(memory.moving)
        assertEquals("driving", memory.activity)
        memory = PresenceLogic.onActivity(memory, "walking", start)
        assertFalse(memory.driving)
        assertTrue(memory.moving)
        memory = PresenceLogic.onActivity(memory, "still", start)
        assertFalse(memory.driving)
        assertFalse(memory.moving)
    }

    @Test
    fun snapshotKeepsApprovedCoordinates() {
        val memory = PresenceLogic.onFix(PresenceMemory.empty(), 33.5, -117.15, 18.0, start, places)
        val snap = PresenceLogic.snapshot(memory, start + 5 * 60_000L, enabled = true, paused = false, dnd = "priority", source = "periodic")
        assertEquals("Work", snap.place)
        assertEquals(5, snap.minutesAtPlace)
        assertEquals(33.5, snap.latitude!!, 0.0001)
        assertEquals(-117.15, snap.longitude!!, 0.0001)
        assertEquals(18.0, snap.accuracyMeters!!, 0.01)
        assertEquals("priority", snap.dnd)
        assertTrue(snap.enabled)
        assertFalse(snap.paused)
    }

    @Test
    fun detectedActivityIntsMapToNamesWithoutThePlayServicesSdk() {
        assertEquals("driving", PresenceLogic.activityName(0))
        assertEquals("walking", PresenceLogic.activityName(7))
        assertEquals("still", PresenceLogic.activityName(3))
        assertEquals("unknown", PresenceLogic.activityName(99))
    }
}
