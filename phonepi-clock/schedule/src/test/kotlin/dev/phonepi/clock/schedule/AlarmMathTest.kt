package dev.phonepi.clock.schedule

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmMathTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val ny = ZoneId.of("America/New_York")

    @Test
    fun dayMaskKeywordsMatchTheDocumentedBits() {
        assertEquals(1, DayMask.MONDAY)
        assertEquals(2, DayMask.TUESDAY)
        assertEquals(4, DayMask.WEDNESDAY)
        assertEquals(8, DayMask.THURSDAY)
        assertEquals(16, DayMask.FRIDAY)
        assertEquals(32, DayMask.SATURDAY)
        assertEquals(64, DayMask.SUNDAY)
        assertEquals(31, DayMask.parse("weekdays"))
        assertEquals(96, DayMask.parse("weekends"))
        assertEquals(127, DayMask.parse("daily"))
        assertEquals(1 or 32, DayMask.parse(listOf("monday", "sat")))
        assertEquals(0, DayMask.parse(null))
        assertNull(DayMask.parse("funday"))
        assertNull(DayMask.parse(128))
    }

    @Test
    fun oneTimeUsesTheNextWallClockAndSkipCancelsIt() {
        val fridayEvening = ZonedDateTime.parse("2026-10-23T18:00:00-07:00")
        val next = nextRing(7, 45, 0, skipNext = false, fridayEvening)
        assertEquals(ZonedDateTime.parse("2026-10-24T07:45:00-07:00"), next)
        assertNull(nextRing(7, 45, 0, skipNext = true, fridayEvening))
    }

    @Test
    fun oneTimeLaterTodayStaysToday() {
        val morning = ZonedDateTime.parse("2026-10-24T06:00:00-07:00")
        assertEquals(
            ZonedDateTime.parse("2026-10-24T07:45:00-07:00"),
            nextRing(7, 45, 0, skipNext = false, morning),
        )
    }

    @Test
    fun exactNowWaitsForTheFollowingDay() {
        val now = ZonedDateTime.parse("2026-10-24T07:45:00-07:00")
        assertEquals(
            ZonedDateTime.parse("2026-10-25T07:45:00-07:00"),
            nextRing(7, 45, 0, skipNext = false, now),
        )
    }

    @Test
    fun saturdayRepeatSkipsOtherDaysAndSkipNextSkipsOneSaturday() {
        val friday = ZonedDateTime.parse("2026-10-23T12:00:00-07:00")
        assertEquals(
            ZonedDateTime.parse("2026-10-24T07:45:00-07:00"),
            nextRing(7, 45, DayMask.SATURDAY, skipNext = false, friday),
        )
        assertEquals(
            ZonedDateTime.parse("2026-10-31T07:45:00-07:00"),
            nextRing(7, 45, DayMask.SATURDAY, skipNext = true, friday),
        )
    }

    @Test
    fun springForwardGapRingsAtTheNextValidMinute() {
        // 2026-03-08, America/Los_Angeles: 02:00 PST jumps to 03:00 PDT.
        val before = ZonedDateTime.of(2026, 3, 8, 0, 30, 0, 0, la)
        val ring = nextRing(2, 30, DayMask.SUNDAY, skipNext = false, before)
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, la), ring)
        assertEquals(
            ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, la),
            resolveWallTime(LocalDate.of(2026, 3, 8), 2, 30, la),
        )
    }

    @Test
    fun fallBackRingsOnceOnTheEarlierOffset() {
        // 2026-11-01: 02:00 PDT falls back to 01:00 PST. 01:30 happens twice.
        val before = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, la)
        val first = nextRing(1, 30, DayMask.SUNDAY, skipNext = false, before)
        assertEquals(ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, la).withEarlierOffsetAtOverlap(), first)

        val between = ZonedDateTime.of(2026, 11, 1, 1, 40, 0, 0, la).withEarlierOffsetAtOverlap()
        val followingWeek = nextRing(1, 30, DayMask.SUNDAY, skipNext = false, between)
        assertEquals(ZonedDateTime.of(2026, 11, 8, 1, 30, 0, 0, la), followingWeek)
    }

    @Test
    fun zoneMoveKeepsTheWallClockInTheNewZone() {
        val laNow = ZonedDateTime.parse("2026-10-24T06:00:00-07:00").withZoneSameInstant(la)
        val nyNow = laNow.withZoneSameInstant(ny)
        val laRing = nextRing(7, 45, 0, false, laNow)!!
        val nyRing = nextRing(7, 45, 0, false, nyNow)!!
        assertEquals(7, laRing.hour)
        assertEquals(45, laRing.minute)
        assertEquals(la, laRing.zone)
        assertEquals(7, nyRing.hour)
        assertEquals(45, nyRing.minute)
        assertEquals(ny, nyRing.zone)
        assertTrue(laRing.toInstant() != nyRing.toInstant())
    }
}
