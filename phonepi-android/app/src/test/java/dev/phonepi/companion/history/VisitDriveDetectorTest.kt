package dev.phonepi.companion.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitDriveDetectorTest {
    @Test
    fun dwellBecomesAVisitAndADepartureClosesIt() {
        val detector = VisitDriveDetector()
        val start = 1_700_000_000_000L
        val first = detector.onFix(fix(start, 33.2000, -117.2400))
        assertTrue(first.isEmpty())
        val open = detector.onFix(fix(start + DWELL_MS, 33.2001, -117.2401))
        assertEquals(1, open.size)
        assertEquals("visit", open[0].kind)
        assertNull(open[0].endMs)
        assertEquals(start, open[0].startMs)
        val closed = detector.onFix(fix(start + DWELL_MS + 60_000L, 33.2200, -117.2400))
        assertEquals(1, closed.size)
        assertEquals(start + DWELL_MS, closed[0].endMs)
        assertEquals(open[0].id, closed[0].id)
    }

    @Test
    fun aShortStopIsNotAVisit() {
        val detector = VisitDriveDetector()
        val start = 1_700_000_000_000L
        detector.onFix(fix(start, 33.2, -117.24))
        val left = detector.onFix(fix(start + 2 * 60_000L, 33.22, -117.24))
        assertTrue(left.isEmpty())
    }

    @Test
    fun vehicleMotionClosesAVisitAndRecordsDriveDistance() {
        val detector = VisitDriveDetector()
        val start = 1_700_000_000_000L
        detector.onFix(fix(start, 33.2, -117.24))
        detector.onFix(fix(start + DWELL_MS, 33.2, -117.24))
        val left = detector.onMotion(MotionHint.VEHICLE_START, start + DWELL_MS + 1_000L)
        assertEquals("visit", left.single().kind)
        assertEquals(start + DWELL_MS, left.single().endMs)
        detector.onFix(fix(start + DWELL_MS + 2 * 60_000L, 33.21, -117.24, speed = 12f))
        val during = detector.onFix(fix(start + DWELL_MS + 4 * 60_000L, 33.23, -117.24, speed = 12f))
        assertTrue(during.any { it.kind == "drive" && (it.distanceMeters ?: 0.0) >= MIN_DRIVE_METERS })
        val done = detector.onMotion(MotionHint.VEHICLE_STOP, start + DWELL_MS + 6 * 60_000L)
        val drive = done.single()
        assertEquals("drive", drive.kind)
        assertTrue((drive.distanceMeters ?: 0.0) >= MIN_DRIVE_METERS)
        assertEquals(start + DWELL_MS + 6 * 60_000L, drive.endMs)
    }

    @Test
    fun aPoorFixIsIgnored() {
        val detector = VisitDriveDetector()
        val changes = detector.onFix(fix(1_000L, 33.2, -117.24, accuracy = 500f))
        assertTrue(changes.isEmpty())
    }

    private fun fix(
        timeMs: Long,
        lat: Double,
        lng: Double,
        accuracy: Float = 20f,
        speed: Float? = 0f,
    ) = LocationFix(timeMs, lat, lng, accuracy, speed)
}
