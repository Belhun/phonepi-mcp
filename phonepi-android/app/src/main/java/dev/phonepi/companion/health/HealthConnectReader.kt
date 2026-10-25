package dev.phonepi.companion.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads daily totals from Health Connect. Never returns raw heart-rate samples.
 */
object HealthConnectReader {
    suspend fun read(context: Context, nowMs: Long = System.currentTimeMillis()): HealthSummary {
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        val enabled = HealthConnectPrefs.enabled(app)
        val paused = HealthConnectPrefs.paused(app)
        val permissions = HealthPermissions.grantedFlags(app)
        if (!HealthPermissions.sdkAvailable(app) || !HealthConnectPrefs.canSync(app)) {
            return buildHealthSummary(
                nowMs = nowMs,
                zone = zone,
                enabled = enabled,
                paused = paused,
                permissions = permissions,
                sleep = emptyList(),
                stepsByDate = emptyMap(),
                restingBpm = null,
                averageBpm = null,
                workouts = emptyList(),
                oxygenSaturationPct = null,
            )
        }
        return withContext(Dispatchers.IO) {
            val client = HealthConnectClient.getOrCreate(app)
            val sleep = if (permissions.sleep) readSleep(client, nowMs) else emptyList()
            val steps = if (permissions.steps) readSteps(client, nowMs, zone) else emptyMap()
            val resting = if (permissions.restingHeartRate) {
                aggregateLong(client, RestingHeartRateRecord.BPM_AVG, startOfLocalDay(nowMs, zone), nowMs)
            } else {
                null
            }
            val average = if (permissions.heartRate) {
                aggregateLong(client, HeartRateRecord.BPM_AVG, startOfLocalDay(nowMs, zone), nowMs)
            } else {
                null
            }
            val workouts = if (permissions.exercise) readWorkouts(client, nowMs) else emptyList()
            val oxygen = if (permissions.oxygenSaturation) {
                readOxygen(client, startOfLocalDay(nowMs, zone), nowMs)
            } else {
                null
            }
            buildHealthSummary(
                nowMs = nowMs,
                zone = zone,
                enabled = enabled,
                paused = paused,
                permissions = permissions,
                sleep = sleep,
                stepsByDate = steps,
                restingBpm = resting?.toInt(),
                averageBpm = average?.toInt(),
                workouts = workouts,
                oxygenSaturationPct = oxygen,
            )
        }
    }

    private suspend fun readSleep(client: HealthConnectClient, nowMs: Long): List<SleepSessionInput> {
        val from = Instant.ofEpochMilli(nowMs - 48 * 3_600_000L)
        val to = Instant.ofEpochMilli(nowMs)
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
            ),
        )
        return response.records.map { record ->
            SleepSessionInput(
                startMs = record.startTime.toEpochMilli(),
                endMs = record.endTime.toEpochMilli(),
                stages = record.stages.map { stage ->
                    SleepStageInput(
                        startMs = stage.startTime.toEpochMilli(),
                        endMs = stage.endTime.toEpochMilli(),
                        type = sleepStageName(stage.stage),
                    )
                },
            )
        }
    }

    private suspend fun readSteps(
        client: HealthConnectClient,
        nowMs: Long,
        zone: ZoneId,
    ): Map<String, Int> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val out = mutableMapOf<String, Int>()
        for (offset in 0L..6L) {
            val day = today.minusDays(offset)
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
                .coerceAtMost(Instant.ofEpochMilli(nowMs))
            if (!end.isAfter(start)) continue
            val total = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )[StepsRecord.COUNT_TOTAL] ?: 0L
            out[day.format(DateTimeFormatter.ISO_LOCAL_DATE)] = total.toInt().coerceAtLeast(0)
        }
        return out
    }

    private suspend fun readWorkouts(client: HealthConnectClient, nowMs: Long): List<WorkoutInput> {
        val from = Instant.ofEpochMilli(nowMs - 7 * 24 * 3_600_000L)
        val to = Instant.ofEpochMilli(nowMs)
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
            ),
        )
        return response.records.map { record ->
            WorkoutInput(
                startMs = record.startTime.toEpochMilli(),
                endMs = record.endTime.toEpochMilli(),
                type = exerciseTypeName(record.exerciseType),
                title = record.title,
            )
        }
    }

    private suspend fun aggregateLong(
        client: HealthConnectClient,
        metric: androidx.health.connect.client.aggregate.AggregateMetric<Long>,
        startMs: Long,
        endMs: Long,
    ): Long? {
        if (endMs <= startMs) return null
        return client.aggregate(
            AggregateRequest(
                metrics = setOf(metric),
                timeRangeFilter = TimeRangeFilter.between(
                    Instant.ofEpochMilli(startMs),
                    Instant.ofEpochMilli(endMs),
                ),
            ),
        )[metric]
    }

    private suspend fun readOxygen(client: HealthConnectClient, startMs: Long, endMs: Long): Int? {
        if (endMs <= startMs) return null
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    Instant.ofEpochMilli(startMs),
                    Instant.ofEpochMilli(endMs),
                ),
            ),
        )
        if (response.records.isEmpty()) return null
        val avg = response.records.map { it.percentage.value }.average()
        return avg.toInt().coerceIn(0, 100)
    }

    private fun startOfLocalDay(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()

    private fun sleepStageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
        SleepSessionRecord.STAGE_TYPE_REM -> "REM"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
        else -> "UNKNOWN"
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "RUNNING"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALKING"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "BIKING"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "STRENGTH_TRAINING"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "YOGA"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        -> "SWIMMING"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "HIKING"
        else -> "OTHER"
    }
}
