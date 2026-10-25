package dev.phonepi.companion.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HealthSummarizerTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val nowMs = 1_792_940_400_000L // 2026-10-25 08:00 America/Los_Angeles

    @Test
    fun lastNightSleepPicksTheLongestSessionThatEndedAfterYesterdayEvening() {
        val nap = session(
            start = nowMs - 2 * HOUR,
            end = nowMs - HOUR,
            deep = 40,
        )
        val overnight = session(
            start = nowMs - 14 * HOUR,
            end = nowMs - 6 * HOUR,
            awake = 22,
            light = 180,
            deep = 90,
            rem = 80,
            unspecified = 40,
        )
        val older = session(
            start = nowMs - 40 * HOUR,
            end = nowMs - 32 * HOUR,
            deep = 200,
        )
        val picked = pickLastNightSleep(listOf(nap, overnight, older), nowMs, zone)
        assertEquals(overnight.startMs, picked?.startMs)
        assertEquals(overnight.endMs, picked?.endMs)
        val stages = stageMinutes(overnight)
        assertEquals(22, stages.awakeMinutes)
        assertEquals(180, stages.lightMinutes)
        assertEquals(90, stages.deepMinutes)
        assertEquals(80, stages.remMinutes)
        assertEquals(40, stages.unspecifiedMinutes)
        assertEquals(412, stages.totalMinutes)
    }

    @Test
    fun lastNightSleepEndIsTheSessionEndForClockBedtimeWake() {
        val overnight = session(
            start = nowMs - 14 * HOUR,
            end = nowMs - 6 * HOUR,
            light = 400,
        )
        val summary = buildHealthSummary(
            nowMs = nowMs,
            zone = zone,
            enabled = true,
            paused = false,
            permissions = allGranted(),
            sleep = listOf(overnight),
            stepsByDate = mapOf("2026-10-25" to 8432, "2026-10-24" to 8600),
            restingBpm = 58,
            averageBpm = 72,
            workouts = listOf(
                WorkoutInput(
                    startMs = nowMs - 20 * HOUR,
                    endMs = nowMs - 19 * HOUR,
                    type = "RUNNING",
                    title = "Evening run",
                ),
            ),
            oxygenSaturationPct = 97,
        )
        assertEquals(overnight.endMs, summary.lastNightSleepEndMs)
        assertEquals(overnight.endMs, summary.lastNightSleep?.endMs)
        assertEquals(8432, summary.steps.today)
        assertEquals(2, summary.steps.last7Days.size)
        assertTrue(summary.steps.last7Days.any { it.date == "2026-10-25" && it.count == 8432 })
        assertEquals(58, summary.heartRate.restingBpm)
        assertEquals(72, summary.heartRate.averageBpm)
        assertEquals(1, summary.workouts.size)
        assertEquals("RUNNING", summary.workouts[0].type)
        assertEquals(SOURCE_LAG_NOTE, summary.sourceLagNote)
        assertNull(summary.heartRateSamples)
    }

    @Test
    fun missingSleepReturnsNullEndTime() {
        val summary = buildHealthSummary(
            nowMs = nowMs,
            zone = zone,
            enabled = true,
            paused = false,
            permissions = allGranted(),
            sleep = emptyList(),
            stepsByDate = emptyMap(),
            restingBpm = null,
            averageBpm = null,
            workouts = emptyList(),
            oxygenSaturationPct = null,
        )
        assertNull(summary.lastNightSleep)
        assertNull(summary.lastNightSleepEndMs)
        assertEquals(0, summary.steps.today)
        assertEquals(0, summary.workouts.size)
    }

    @Test
    fun workoutsAreDailyTotalsNotHeartRateStreams() {
        val json = healthSummaryJson(
            buildHealthSummary(
                nowMs = nowMs,
                zone = zone,
                enabled = true,
                paused = false,
                permissions = allGranted(),
                sleep = emptyList(),
                stepsByDate = emptyMap(),
                restingBpm = 60,
                averageBpm = 80,
                workouts = listOf(
                    WorkoutInput(nowMs - HOUR, nowMs - HOUR + 30 * 60_000L, "WALKING", null),
                ),
                oxygenSaturationPct = null,
            ),
        )
        assertTrue(json.contains("\"averageBpm\":80"))
        assertTrue(!json.contains("heartRateSamples"))
        assertTrue(!json.contains("bpmSamples"))
        assertTrue(json.contains("lastNightSleepEndMs"))
    }

    private fun allGranted() = HealthPermissionFlags(
        sleep = true,
        steps = true,
        heartRate = true,
        restingHeartRate = true,
        exercise = true,
        oxygenSaturation = true,
        background = true,
        history = true,
    )

    private fun session(
        start: Long,
        end: Long,
        awake: Int = 0,
        light: Int = 0,
        deep: Int = 0,
        rem: Int = 0,
        unspecified: Int = 0,
    ): SleepSessionInput {
        val stages = mutableListOf<SleepStageInput>()
        var cursor = start
        fun add(type: String, minutes: Int) {
            if (minutes <= 0) return
            val next = cursor + minutes * 60_000L
            stages.add(SleepStageInput(cursor, next, type))
            cursor = next
        }
        add("AWAKE", awake)
        add("LIGHT", light)
        add("DEEP", deep)
        add("REM", rem)
        add("UNKNOWN", unspecified)
        if (stages.isEmpty()) stages.add(SleepStageInput(start, end, "SLEEPING"))
        return SleepSessionInput(start, end, stages)
    }

    companion object {
        private const val HOUR = 3_600_000L
    }
}
