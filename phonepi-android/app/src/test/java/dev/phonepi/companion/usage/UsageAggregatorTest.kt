package dev.phonepi.companion.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageAggregatorTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val utc = ZoneId.of("UTC")

    @Test
    fun screenOnSessionThatCrossesMidnightSplitsAcrossLocalDates() {
        val day1 = LocalDate.of(2026, 10, 24)
        val on = zoned(la, day1, 23, 50)
        val off = zoned(la, day1.plusDays(1), 0, 10)
        val now = zoned(la, day1.plusDays(1), 12, 0)
        val days = rollupUsage(
            events = listOf(
                UsageEvent(UsageEventType.SCREEN_INTERACTIVE, on),
                UsageEvent(UsageEventType.SCREEN_NON_INTERACTIVE, off),
            ),
            appStats = emptyList(),
            stored = emptyList(),
            zone = la,
            nowMs = now,
            eventWindowStartMs = dayStartMs(day1, la),
        )
        val first = days.single { it.date == "2026-10-24" }
        val second = days.single { it.date == "2026-10-25" }
        assertEquals(10 * 60 * 1000L, first.screenOnMs)
        assertEquals(10 * 60 * 1000L, second.screenOnMs)
        assertEquals(0, first.unlockCount)
        assertEquals(0, second.unlockCount)
    }

    @Test
    fun sameInstantMapsToDifferentLocalDatesInPacificAndUtc() {
        val instant = ZonedDateTime.of(2026, 10, 25, 23, 30, 0, 0, la).toInstant().toEpochMilli()
        val now = instant + 60_000L
        val events = listOf(
            UsageEvent(UsageEventType.KEYGUARD_HIDDEN, instant),
            UsageEvent(UsageEventType.ACTIVITY_RESUMED, instant, "com.android.chrome"),
        )
        val pacific = rollupUsage(
            events = events,
            appStats = emptyList(),
            stored = emptyList(),
            zone = la,
            nowMs = now,
            eventWindowStartMs = dayStartMs(LocalDate.of(2026, 10, 25), la),
        )
        val utcDays = rollupUsage(
            events = events,
            appStats = emptyList(),
            stored = emptyList(),
            zone = utc,
            nowMs = now,
            eventWindowStartMs = dayStartMs(LocalDate.of(2026, 10, 25), utc),
        )
        assertEquals("2026-10-25", pacific.single { it.unlockCount == 1 }.date)
        assertEquals("2026-10-26", utcDays.single { it.unlockCount == 1 }.date)
    }

    @Test
    fun springForwardDayUsesElapsedTimeNotWallClock() {
        // 2026-03-08 in America/Los_Angeles: 01:59 PST -> 03:00 PDT.
        val day = LocalDate.of(2026, 3, 8)
        val on = zoned(la, day, 1, 50)
        val off = zoned(la, day, 3, 10)
        val now = zoned(la, day, 12, 0)
        val days = rollupUsage(
            events = listOf(
                UsageEvent(UsageEventType.SCREEN_INTERACTIVE, on),
                UsageEvent(UsageEventType.SCREEN_NON_INTERACTIVE, off),
            ),
            appStats = emptyList(),
            stored = emptyList(),
            zone = la,
            nowMs = now,
            eventWindowStartMs = dayStartMs(day, la),
        )
        val rolled = days.single { it.date == "2026-03-08" }
        // 01:50 PST = 09:50 UTC, 03:10 PDT = 10:10 UTC → 20 minutes elapsed.
        assertEquals(20 * 60 * 1000L, rolled.screenOnMs)
        assertEquals(1, days.count { it.date == "2026-03-08" })
    }

    @Test
    fun fallBackDayKeepsASingleLocalDate() {
        // 2026-11-01 in America/Los_Angeles: 02:00 PDT -> 01:00 PST.
        val day = LocalDate.of(2026, 11, 1)
        val on = zoned(la, day, 0, 50)
        val off = zoned(la, day, 2, 10)
        val now = zoned(la, day, 12, 0)
        val days = rollupUsage(
            events = listOf(
                UsageEvent(UsageEventType.SCREEN_INTERACTIVE, on),
                UsageEvent(UsageEventType.SCREEN_NON_INTERACTIVE, off),
            ),
            appStats = emptyList(),
            stored = emptyList(),
            zone = la,
            nowMs = now,
            eventWindowStartMs = dayStartMs(day, la),
        )
        val rolled = days.single { it.date == "2026-11-01" }
        // 00:50 PDT = 07:50 UTC, 02:10 PST = 10:10 UTC → 2h 20m elapsed.
        assertEquals((2 * 60 + 20) * 60 * 1000L, rolled.screenOnMs)
    }

    @Test
    fun unpairedScreenOffIsIgnoredAndOpenScreenCountsUntilNow() {
        val day = LocalDate.of(2026, 10, 24)
        val strayOff = zoned(la, day, 8, 0)
        val on = zoned(la, day, 9, 0)
        val now = zoned(la, day, 9, 15)
        val days = rollupUsage(
            events = listOf(
                UsageEvent(UsageEventType.SCREEN_NON_INTERACTIVE, strayOff),
                UsageEvent(UsageEventType.SCREEN_INTERACTIVE, on),
            ),
            appStats = emptyList(),
            stored = emptyList(),
            zone = la,
            nowMs = now,
            eventWindowStartMs = dayStartMs(day, la),
        )
        assertEquals(15 * 60 * 1000L, days.single { it.date == "2026-10-24" }.screenOnMs)
    }

    @Test
    fun unlocksBucketByLocalDateAndLastUsedTakesTheLatestEvent() {
        val day = LocalDate.of(2026, 10, 24)
        val firstUnlock = zoned(la, day, 7, 0)
        val secondUnlock = zoned(la, day, 18, 0)
        val nextUnlock = zoned(la, day.plusDays(1), 0, 1)
        val lastResume = zoned(la, day, 21, 30)
        val now = zoned(la, day.plusDays(1), 8, 0)
        val days = rollupUsage(
            events = listOf(
                UsageEvent(UsageEventType.KEYGUARD_HIDDEN, firstUnlock),
                UsageEvent(UsageEventType.ACTIVITY_RESUMED, lastResume, "com.google.android.youtube"),
                UsageEvent(UsageEventType.KEYGUARD_HIDDEN, secondUnlock),
                UsageEvent(UsageEventType.KEYGUARD_HIDDEN, nextUnlock),
            ),
            appStats = emptyList(),
            stored = emptyList(),
            zone = la,
            nowMs = now,
            eventWindowStartMs = dayStartMs(day, la),
        )
        val first = days.single { it.date == "2026-10-24" }
        val second = days.single { it.date == "2026-10-25" }
        assertEquals(2, first.unlockCount)
        assertEquals(1, second.unlockCount)
        assertEquals(lastResume, first.lastUsedAt)
        assertEquals(nextUnlock, second.lastUsedAt)
    }

    @Test
    fun daysOutsideTheEventWindowKeepTheStoredUnlockRollup() {
        val old = LocalDate.of(2026, 10, 20)
        val today = LocalDate.of(2026, 10, 24)
        val stored = listOf(
            UsageDay(
                date = "2026-10-20",
                timeZone = la.id,
                screenOnMs = 3_600_000L,
                unlockCount = 17,
                lastUsedAt = dayStartMs(old, la) + 12 * 3_600_000L,
                apps = listOf(
                    UsageApp("com.android.chrome", "Chrome", 1_200_000L, 1L),
                ),
                updatedAtMs = 1L,
            ),
        )
        val windowStart = dayStartMs(today.minusDays(3), la)
        val now = zoned(la, today, 12, 0)
        val days = rollupUsage(
            events = listOf(
                UsageEvent(UsageEventType.KEYGUARD_HIDDEN, zoned(la, today, 8, 0)),
            ),
            appStats = listOf(
                DailyAppStats("2026-10-20", "com.android.chrome", "Chrome", 2_000_000L, 9L),
            ),
            stored = stored,
            zone = la,
            nowMs = now,
            eventWindowStartMs = windowStart,
            updatedAtMs = now,
        )
        val kept = days.single { it.date == "2026-10-20" }
        assertEquals(17, kept.unlockCount)
        assertEquals(3_600_000L, kept.screenOnMs)
        assertEquals(2_000_000L, kept.apps.single().foregroundMs)
        val todayDay = days.single { it.date == "2026-10-24" }
        assertEquals(1, todayDay.unlockCount)
    }

    @Test
    fun aQuietDayInsideTheEventWindowKeepsTheStoredUnlockRollup() {
        val day = LocalDate.of(2026, 10, 22)
        val today = LocalDate.of(2026, 10, 24)
        val stored = listOf(
            UsageDay(
                date = "2026-10-22",
                timeZone = la.id,
                screenOnMs = 900_000L,
                unlockCount = 9,
                lastUsedAt = dayStartMs(day, la),
                apps = emptyList(),
                updatedAtMs = 1L,
            ),
        )
        val days = rollupUsage(
            events = emptyList(),
            appStats = emptyList(),
            stored = stored,
            zone = la,
            nowMs = zoned(la, today, 12, 0),
            eventWindowStartMs = dayStartMs(today.minusDays(3), la),
        )
        val kept = days.single { it.date == "2026-10-22" }
        assertEquals(9, kept.unlockCount)
        assertEquals(900_000L, kept.screenOnMs)
    }

    @Test
    fun appTotalsMergeByPackageAndDropZeroMinuteAppsFromTheCapOrder() {
        val day = "2026-10-24"
        val days = rollupUsage(
            events = emptyList(),
            appStats = listOf(
                DailyAppStats(day, "com.google.android.youtube", "YouTube", 3_000_000L, 8L),
                DailyAppStats(day, "com.google.android.youtube", "YouTube", 600_000L, 9L),
                DailyAppStats(day, "com.android.chrome", "Chrome", 120_000L, 7L),
                DailyAppStats(day, "com.example.empty", "Empty", 0L, 1L),
            ),
            stored = emptyList(),
            zone = la,
            nowMs = zoned(la, LocalDate.of(2026, 10, 24), 12, 0),
            eventWindowStartMs = dayStartMs(LocalDate.of(2026, 10, 24), la),
            maxApps = 10,
        )
        val apps = days.single { it.date == day }.apps
        assertEquals(listOf("com.google.android.youtube", "com.android.chrome"), apps.map { it.packageName })
        assertEquals(3_600_000L, apps[0].foregroundMs)
        assertEquals(9L, apps[0].lastUsedAt)
        assertEquals(2, msToMinutes(90_000L))
        assertEquals(0, msToMinutes(0L))
        assertNull(days.single { it.date == day }.lastUsedAt?.takeIf { it == 0L })
        assertTrue(days.single { it.date == day }.lastUsedAt == 9L)
    }

    private fun zoned(zone: ZoneId, date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
