package dev.phonepi.companion.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** UsageEvents.Event type ids. Kept as ints so unit tests do not need the Android SDK. */
object UsageEventType {
    const val MOVE_TO_FOREGROUND = 1
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_HIDDEN = 18
    const val DEVICE_SHUTDOWN = 26
    const val ACTIVITY_RESUMED = 23
}

const val USAGE_RETENTION_DAYS = 90
const val USAGE_EVENT_LOOKBACK_DAYS = 4
const val USAGE_STATS_LOOKBACK_DAYS = 14
const val USAGE_MAX_APPS_STORED = 50
const val USAGE_COLLECT_INTERVAL_MS = 15 * 60 * 1000L
const val USAGE_LABEL_MAX = 80
const val USAGE_PACKAGE_MAX = 256

data class UsageEvent(
    val type: Int,
    val timestampMs: Long,
    val packageName: String = "",
)

data class DailyAppStats(
    val date: String,
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val lastUsedAt: Long,
)

data class UsageApp(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val lastUsedAt: Long,
)

data class UsageDay(
    val date: String,
    val timeZone: String,
    val screenOnMs: Long,
    val unlockCount: Int,
    val lastUsedAt: Long?,
    val apps: List<UsageApp>,
    val updatedAtMs: Long,
)

data class UsageMeta(
    val enabled: Boolean,
    val paused: Boolean,
    val usageAccessGranted: Boolean,
    val timeZone: String,
    val lastUsedAt: Long?,
    val updatedAtMs: Long,
)

fun localDateOf(epochMs: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

fun dayStartMs(date: LocalDate, zone: ZoneId): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()

fun dayEndExclusiveMs(date: LocalDate, zone: ZoneId): Long =
    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

fun msToMinutes(ms: Long): Int {
    if (ms <= 0L) return 0
    return ((ms + 30_000L) / 60_000L).toInt()
}

fun isLastUsedEvent(type: Int): Boolean = when (type) {
    UsageEventType.SCREEN_INTERACTIVE,
    UsageEventType.ACTIVITY_RESUMED,
    UsageEventType.MOVE_TO_FOREGROUND,
    -> true
    else -> false
}
