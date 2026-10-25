package dev.phonepi.companion.usage

import java.time.LocalDate
import java.time.ZoneId

/**
 * Totals UsageStats / UsageEvents into local-calendar days.
 * Screen-on sessions that cross midnight are split. Time zone and DST
 * use [java.time] so a 23-hour spring-forward day and a 25-hour fall-back
 * day still bucket by local date.
 */
fun rollupUsage(
    events: List<UsageEvent>,
    appStats: List<DailyAppStats>,
    stored: List<UsageDay>,
    zone: ZoneId,
    nowMs: Long,
    eventWindowStartMs: Long,
    updatedAtMs: Long = nowMs,
    maxApps: Int = USAGE_MAX_APPS_STORED,
): List<UsageDay> {
    val screenOnMs = mutableMapOf<LocalDate, Long>()
    val unlocks = mutableMapOf<LocalDate, Int>()
    val lastUsed = mutableMapOf<LocalDate, Long>()

    var screenOnAt: Long? = null
    for (event in events.sortedBy { it.timestampMs }) {
        if (event.timestampMs < eventWindowStartMs || event.timestampMs > nowMs) continue
        when (event.type) {
            UsageEventType.SCREEN_INTERACTIVE -> {
                if (screenOnAt == null) screenOnAt = event.timestampMs
                bumpLastUsed(lastUsed, event.timestampMs, zone)
            }
            UsageEventType.SCREEN_NON_INTERACTIVE, UsageEventType.DEVICE_SHUTDOWN -> {
                val start = screenOnAt
                if (start != null && event.timestampMs > start) {
                    addDuration(screenOnMs, start, event.timestampMs, zone)
                }
                screenOnAt = null
            }
            UsageEventType.KEYGUARD_HIDDEN -> {
                val date = localDateOf(event.timestampMs, zone)
                unlocks[date] = (unlocks[date] ?: 0) + 1
                bumpLastUsed(lastUsed, event.timestampMs, zone)
            }
            else -> {
                if (isLastUsedEvent(event.type)) {
                    bumpLastUsed(lastUsed, event.timestampMs, zone)
                }
            }
        }
    }
    val openStart = screenOnAt
    if (openStart != null && nowMs > openStart) {
        addDuration(screenOnMs, openStart, nowMs, zone)
        bumpLastUsed(lastUsed, nowMs, zone)
    }

    val appsByDate = linkedMapOf<String, MutableMap<String, UsageApp>>()
    for (stat in appStats) {
        if (stat.foregroundMs <= 0L) continue
        val pkg = stat.packageName.trim().take(USAGE_PACKAGE_MAX)
        if (pkg.isEmpty()) continue
        val dateMap = appsByDate.getOrPut(stat.date) { linkedMapOf() }
        val prev = dateMap[pkg]
        dateMap[pkg] = UsageApp(
            packageName = pkg,
            label = stat.label.trim().take(USAGE_LABEL_MAX).ifEmpty { pkg },
            foregroundMs = (prev?.foregroundMs ?: 0L) + stat.foregroundMs,
            lastUsedAt = maxOf(prev?.lastUsedAt ?: 0L, stat.lastUsedAt),
        )
    }

    val storedByDate = stored.associateBy { it.date }
    val dates = sortedSetOf<String>()
    dates.addAll(storedByDate.keys)
    dates.addAll(appsByDate.keys)
    for (date in screenOnMs.keys + unlocks.keys + lastUsed.keys) {
        dates.add(date.toString())
    }

    val zoneName = zone.id
    val days = mutableListOf<UsageDay>()
    for (dateText in dates) {
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: continue
        val prev = storedByDate[dateText]
        val fullyCovered = eventWindowStartMs <= dayStartMs(date, zone)
        val eventScreen = screenOnMs[date] ?: 0L
        val eventUnlocks = unlocks[date] ?: 0
        val eventLast = lastUsed[date]
        val sawEvents = eventScreen > 0L || eventUnlocks > 0 || eventLast != null
        val isToday = date == localDateOf(nowMs, zone)
        val apps = (appsByDate[dateText]?.values ?: emptyList())
            .sortedWith(compareByDescending<UsageApp> { it.foregroundMs }.thenBy { it.packageName })
            .take(maxApps)
            .ifEmpty { prev?.apps.orEmpty() }
        val statsLast = apps.maxOfOrNull { it.lastUsedAt }?.takeIf { it > 0L }
        val useEvents = fullyCovered && (sawEvents || isToday || prev == null)
        val day = if (useEvents) {
            UsageDay(
                date = dateText,
                timeZone = zoneName,
                screenOnMs = eventScreen,
                unlockCount = eventUnlocks,
                lastUsedAt = maxOfNullable(eventLast, statsLast),
                apps = apps,
                updatedAtMs = updatedAtMs,
            )
        } else {
            UsageDay(
                date = dateText,
                timeZone = prev?.timeZone ?: zoneName,
                screenOnMs = prev?.screenOnMs ?: 0L,
                unlockCount = prev?.unlockCount ?: 0,
                lastUsedAt = maxOfNullable(prev?.lastUsedAt, statsLast),
                apps = if (appsByDate.containsKey(dateText)) apps else prev?.apps.orEmpty(),
                updatedAtMs = maxOf(prev?.updatedAtMs ?: 0L, updatedAtMs),
            )
        }
        days.add(day)
    }
    return days.sortedBy { it.date }
}

private fun bumpLastUsed(into: MutableMap<LocalDate, Long>, timestampMs: Long, zone: ZoneId) {
    val date = localDateOf(timestampMs, zone)
    val prev = into[date]
    if (prev == null || timestampMs > prev) into[date] = timestampMs
}

private fun addDuration(
    into: MutableMap<LocalDate, Long>,
    fromMs: Long,
    toMs: Long,
    zone: ZoneId,
) {
    if (toMs <= fromMs) return
    var cursor = fromMs
    while (cursor < toMs) {
        val date = localDateOf(cursor, zone)
        val nextMidnight = dayEndExclusiveMs(date, zone)
        val end = minOf(toMs, nextMidnight)
        if (end > cursor) {
            into[date] = (into[date] ?: 0L) + (end - cursor)
        }
        cursor = end
    }
}

private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}
