package dev.phonepi.companion.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class UsageDayStoreTest {
    @Test
    fun writesReadsAndPrunesByRetention() {
        val dir = Files.createTempDirectory("phonepi-usage-").toFile()
        val now = dateMs(2026, 10, 24)
        val store = UsageDayStore(dir, retentionDays = 10, now = { now })
        store.write(
            listOf(
                day("2026-10-24", 1_200_000L, 11),
                day("2026-10-16", 60_000L, 2),
                day("2026-09-01", 9_000_000L, 40),
            ),
        )
        assertEquals(setOf("2026-10-24.json", "2026-10-16.json"), dir.list()?.toSet())
        val loaded = store.readAll()
        assertEquals(listOf("2026-10-16", "2026-10-24"), loaded.map { it.date })
        assertEquals(11, store.read("2026-10-24")?.unlockCount)
        assertNull(store.read("2026-09-01"))
        store.clear()
        assertTrue(store.readAll().isEmpty())
        assertEquals(0, dir.list()?.count { it.endsWith(".json") })
    }

    @Test
    fun jsonRoundTripDropsSecretFields() {
        val day = day("2026-10-24", 600_000L, 3).copy(
            apps = listOf(UsageApp("com.android.chrome", "Chrome", 120_000L, 5L)),
        )
        val parsed = parseUsageDay(usageDayJson(day))
        assertEquals(day, parsed)
        assertNull(parseUsageDay("""{"date":"2026-10-24","timeZone":"UTC","screenOnMs":1,"unlockCount":0,"updatedAtMs":1,"token":"nope"}"""))
        assertNull(parseUsageDay("""{"date":"bad","timeZone":"UTC","screenOnMs":1,"unlockCount":0,"updatedAtMs":1}"""))
    }

    private fun day(date: String, screenOnMs: Long, unlocks: Int) = UsageDay(
        date = date,
        timeZone = "America/Los_Angeles",
        screenOnMs = screenOnMs,
        unlockCount = unlocks,
        lastUsedAt = 10L,
        apps = emptyList(),
        updatedAtMs = 20L,
    )

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
}
