package dev.phonepi.companion.health

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val SOURCE_LAG_NOTE =
    "Samsung Health can lag minutes to hours before Health Connect has the latest sleep and heart-rate data."

const val HEALTH_SYNC_INTERVAL_HOURS = 1L
private const val HOUR_MS = 3_600_000L
private const val MINUTE_MS = 60_000L

data class SleepStageInput(
    val startMs: Long,
    val endMs: Long,
    val type: String,
)

data class SleepSessionInput(
    val startMs: Long,
    val endMs: Long,
    val stages: List<SleepStageInput>,
)

data class WorkoutInput(
    val startMs: Long,
    val endMs: Long,
    val type: String,
    val title: String?,
)

data class HealthPermissionFlags(
    val sleep: Boolean,
    val steps: Boolean,
    val heartRate: Boolean,
    val restingHeartRate: Boolean,
    val exercise: Boolean,
    val oxygenSaturation: Boolean,
    val background: Boolean,
    val history: Boolean,
) {
    companion object {
        val NONE = HealthPermissionFlags(
            sleep = false,
            steps = false,
            heartRate = false,
            restingHeartRate = false,
            exercise = false,
            oxygenSaturation = false,
            background = false,
            history = false,
        )
    }
}

data class SleepStageTotals(
    val awakeMinutes: Int,
    val lightMinutes: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    val unspecifiedMinutes: Int,
) {
    val totalMinutes: Int
        get() = awakeMinutes + lightMinutes + deepMinutes + remMinutes + unspecifiedMinutes
}

data class LastNightSleep(
    val startMs: Long,
    val startIso: String,
    val endMs: Long,
    val endIso: String,
    val totalMinutes: Int,
    val stages: SleepStageTotals,
)

data class StepDay(
    val date: String,
    val count: Int,
)

data class WorkoutDay(
    val startMs: Long,
    val startIso: String,
    val endMs: Long,
    val endIso: String,
    val durationMinutes: Int,
    val type: String,
    val title: String?,
)

data class HealthSummary(
    val syncedAtMs: Long,
    val syncedAtIso: String,
    val timezone: String,
    val enabled: Boolean,
    val paused: Boolean,
    val permissions: HealthPermissionFlags,
    val lastNightSleep: LastNightSleep?,
    val lastNightSleepEndMs: Long?,
    val lastNightSleepEndIso: String?,
    val steps: StepsTotals,
    val heartRate: HeartRateTotals,
    val workouts: List<WorkoutDay>,
    val oxygenSaturationPct: Int?,
    val sourceLagNote: String = SOURCE_LAG_NOTE,
    val heartRateSamples: List<Nothing>? = null,
)

data class StepsTotals(
    val today: Int,
    val last7Days: List<StepDay>,
    val last7DaysTotal: Int,
)

data class HeartRateTotals(
    val restingBpm: Int?,
    val averageBpm: Int?,
)

fun pickLastNightSleep(
    sessions: List<SleepSessionInput>,
    nowMs: Long,
    zone: ZoneId,
): SleepSessionInput? {
    val local = Instant.ofEpochMilli(nowMs).atZone(zone)
    val windowStart = local.toLocalDate().minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        .toEpochMilli()
    return sessions
        .filter { it.endMs >= windowStart && it.startMs <= nowMs && it.endMs >= it.startMs }
        .maxWithOrNull(
            compareBy<SleepSessionInput> { it.endMs - it.startMs }
                .thenBy { it.endMs },
        )
}

fun stageMinutes(session: SleepSessionInput): SleepStageTotals {
    var awake = 0
    var light = 0
    var deep = 0
    var rem = 0
    var unspecified = 0
    for (stage in session.stages) {
        val minutes = ((stage.endMs - stage.startMs).coerceAtLeast(0L) / MINUTE_MS).toInt()
        when (stage.type.uppercase()) {
            "AWAKE", "OUT_OF_BED", "STAGE_TYPE_AWAKE", "STAGE_TYPE_OUT_OF_BED" -> awake += minutes
            "LIGHT", "STAGE_TYPE_LIGHT" -> light += minutes
            "DEEP", "STAGE_TYPE_DEEP" -> deep += minutes
            "REM", "STAGE_TYPE_REM" -> rem += minutes
            else -> unspecified += minutes
        }
    }
    if (awake + light + deep + rem + unspecified == 0) {
        unspecified = ((session.endMs - session.startMs).coerceAtLeast(0L) / MINUTE_MS).toInt()
    }
    return SleepStageTotals(awake, light, deep, rem, unspecified)
}

fun buildHealthSummary(
    nowMs: Long,
    zone: ZoneId,
    enabled: Boolean,
    paused: Boolean,
    permissions: HealthPermissionFlags,
    sleep: List<SleepSessionInput>,
    stepsByDate: Map<String, Int>,
    restingBpm: Int?,
    averageBpm: Int?,
    workouts: List<WorkoutInput>,
    oxygenSaturationPct: Int?,
): HealthSummary {
    val picked = pickLastNightSleep(sleep, nowMs, zone)
    val stages = picked?.let { stageMinutes(it) }
    val lastNight = picked?.let { session ->
        LastNightSleep(
            startMs = session.startMs,
            startIso = iso(session.startMs),
            endMs = session.endMs,
            endIso = iso(session.endMs),
            totalMinutes = stages?.totalMinutes
                ?: ((session.endMs - session.startMs) / MINUTE_MS).toInt(),
            stages = stages ?: SleepStageTotals(0, 0, 0, 0, 0),
        )
    }
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val todayKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val days = (0L..6L).map { today.minusDays(it) }
        .map { it.format(DateTimeFormatter.ISO_LOCAL_DATE) }
        .mapNotNull { date -> stepsByDate[date]?.let { StepDay(date, it.coerceAtLeast(0)) } }
        .reversed()
    val cutoff = nowMs - 7 * 24 * HOUR_MS
    val workoutDays = workouts
        .filter { it.endMs >= cutoff && it.endMs <= nowMs && it.endMs >= it.startMs }
        .sortedByDescending { it.endMs }
        .take(10)
        .map {
            WorkoutDay(
                startMs = it.startMs,
                startIso = iso(it.startMs),
                endMs = it.endMs,
                endIso = iso(it.endMs),
                durationMinutes = ((it.endMs - it.startMs) / MINUTE_MS).toInt().coerceAtLeast(0),
                type = it.type,
                title = it.title?.trim()?.take(80)?.ifEmpty { null },
            )
        }
    return HealthSummary(
        syncedAtMs = nowMs,
        syncedAtIso = iso(nowMs),
        timezone = zone.id,
        enabled = enabled,
        paused = paused,
        permissions = permissions,
        lastNightSleep = lastNight,
        lastNightSleepEndMs = lastNight?.endMs,
        lastNightSleepEndIso = lastNight?.endIso,
        steps = StepsTotals(
            today = stepsByDate[todayKey]?.coerceAtLeast(0) ?: 0,
            last7Days = days,
            last7DaysTotal = days.sumOf { it.count },
        ),
        heartRate = HeartRateTotals(restingBpm, averageBpm),
        workouts = workoutDays,
        oxygenSaturationPct = oxygenSaturationPct?.coerceIn(0, 100),
        sourceLagNote = SOURCE_LAG_NOTE,
    )
}

fun healthSummaryJson(summary: HealthSummary): String =
    Json.encodeToString(JsonObject.serializer(), healthSummaryObject(summary))

fun healthSummaryObject(summary: HealthSummary): JsonObject = buildJsonObject {
    put("syncedAtMs", summary.syncedAtMs)
    put("syncedAtIso", summary.syncedAtIso)
    put("timezone", summary.timezone)
    put("enabled", summary.enabled)
    put("paused", summary.paused)
    putJsonObject("permissions") {
        put("sleep", summary.permissions.sleep)
        put("steps", summary.permissions.steps)
        put("heartRate", summary.permissions.heartRate)
        put("restingHeartRate", summary.permissions.restingHeartRate)
        put("exercise", summary.permissions.exercise)
        put("oxygenSaturation", summary.permissions.oxygenSaturation)
        put("background", summary.permissions.background)
        put("history", summary.permissions.history)
    }
    val sleep = summary.lastNightSleep
    if (sleep == null) {
        put("lastNightSleep", JsonNull)
    } else {
        putJsonObject("lastNightSleep") {
            put("startMs", sleep.startMs)
            put("startIso", sleep.startIso)
            put("endMs", sleep.endMs)
            put("endIso", sleep.endIso)
            put("totalMinutes", sleep.totalMinutes)
            putJsonObject("stages") {
                put("awakeMinutes", sleep.stages.awakeMinutes)
                put("lightMinutes", sleep.stages.lightMinutes)
                put("deepMinutes", sleep.stages.deepMinutes)
                put("remMinutes", sleep.stages.remMinutes)
                put("unspecifiedMinutes", sleep.stages.unspecifiedMinutes)
            }
        }
    }
    if (summary.lastNightSleepEndMs == null) {
        put("lastNightSleepEndMs", JsonNull)
        put("lastNightSleepEndIso", JsonNull)
    } else {
        put("lastNightSleepEndMs", summary.lastNightSleepEndMs)
        put("lastNightSleepEndIso", summary.lastNightSleepEndIso ?: iso(summary.lastNightSleepEndMs))
    }
    putJsonObject("steps") {
        put("today", summary.steps.today)
        put("last7DaysTotal", summary.steps.last7DaysTotal)
        putJsonArray("last7Days") {
            summary.steps.last7Days.forEach { day ->
                add(buildJsonObject {
                    put("date", day.date)
                    put("count", day.count)
                })
            }
        }
    }
    putJsonObject("heartRate") {
        val resting = summary.heartRate.restingBpm
        if (resting == null) put("restingBpm", JsonNull) else put("restingBpm", resting)
        val average = summary.heartRate.averageBpm
        if (average == null) put("averageBpm", JsonNull) else put("averageBpm", average)
    }
    putJsonArray("workouts") {
        summary.workouts.forEach { workout ->
            add(buildJsonObject {
                put("startMs", workout.startMs)
                put("startIso", workout.startIso)
                put("endMs", workout.endMs)
                put("endIso", workout.endIso)
                put("durationMinutes", workout.durationMinutes)
                put("type", workout.type)
                val title = workout.title
                if (title == null) put("title", JsonNull) else put("title", title)
            })
        }
    }
    val oxygen = summary.oxygenSaturationPct
    if (oxygen == null) put("oxygenSaturationPct", JsonNull) else put("oxygenSaturationPct", oxygen)
    put("sourceLagNote", summary.sourceLagNote)
}

fun grantStateLine(flags: HealthPermissionFlags, sdkAvailable: Boolean): String {
    if (!sdkAvailable) return "Health Connect is not available on this phone."
    val granted = listOf(
        "Sleep" to flags.sleep,
        "Steps" to flags.steps,
        "Heart rate" to flags.heartRate,
        "Resting heart rate" to flags.restingHeartRate,
        "Exercise" to flags.exercise,
        "Blood oxygen" to flags.oxygenSaturation,
        "Background" to flags.background,
        "Past data" to flags.history,
    )
    val yes = granted.filter { it.second }.map { it.first }
    val no = granted.filter { !it.second }.map { it.first }
    val grantedText = if (yes.isEmpty()) "None granted" else "Granted: ${yes.joinToString(", ")}"
    val missingText = if (no.isEmpty()) "All Health Connect reads granted." else "Needed: ${no.joinToString(", ")}"
    return "$grantedText. $missingText"
}

private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
