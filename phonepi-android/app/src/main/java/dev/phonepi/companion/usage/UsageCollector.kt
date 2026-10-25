package dev.phonepi.companion.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.time.ZoneId

/**
 * Reads UsageStatsManager. queryUsageStats for per-app daily totals.
 * queryEvents for screen-on, unlocks, and last-used. Events are only kept
 * a few days, so [UsageEngine] persists a daily rollup.
 */
object UsageCollector {
    fun collect(context: Context, nowMs: Long = System.currentTimeMillis()): List<UsageDay> {
        if (!UsagePrefs.canCapture(context)) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val today = localDateOf(nowMs, zone)
        val eventStart = dayStartMs(today.minusDays(USAGE_EVENT_LOOKBACK_DAYS.toLong()), zone)
        val statsStart = dayStartMs(today.minusDays(USAGE_STATS_LOOKBACK_DAYS.toLong()), zone)
        val events = queryEvents(usm, eventStart, nowMs)
        val stats = queryDailyStats(usm, context.packageManager, statsStart, nowMs, zone)
        val store = store(context)
        val stored = store.readAll()
        return rollupUsage(
            events = events,
            appStats = stats,
            stored = stored,
            zone = zone,
            nowMs = nowMs,
            eventWindowStartMs = eventStart,
            updatedAtMs = nowMs,
        )
    }

    internal fun store(context: Context): UsageDayStore =
        UsageDayStore(java.io.File(context.applicationContext.filesDir, "usage"))

    private fun queryEvents(usm: UsageStatsManager, beginMs: Long, endMs: Long): List<UsageEvent> {
        val queried = runCatching { usm.queryEvents(beginMs, endMs) }.getOrNull() ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (queried.hasNextEvent()) {
            queried.getNextEvent(event)
            out.add(
                UsageEvent(
                    type = event.eventType,
                    timestampMs = event.timeStamp,
                    packageName = event.packageName.orEmpty(),
                ),
            )
        }
        return out
    }

    private fun queryDailyStats(
        usm: UsageStatsManager,
        pm: PackageManager,
        beginMs: Long,
        endMs: Long,
        zone: java.time.ZoneId,
    ): List<DailyAppStats> {
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginMs, endMs)
        }.getOrNull().orEmpty()
        val labels = HashMap<String, String>()
        return stats.mapNotNull { row ->
            val pkg = row.packageName?.trim().orEmpty()
            if (pkg.isEmpty() || row.totalTimeInForeground <= 0L) return@mapNotNull null
            val date = localDateOf(row.firstTimeStamp, zone).toString()
            DailyAppStats(
                date = date,
                packageName = pkg,
                label = labels.getOrPut(pkg) { appLabel(pm, pkg) },
                foregroundMs = row.totalTimeInForeground,
                lastUsedAt = row.lastTimeUsed,
            )
        }
    }

    private fun appLabel(pm: PackageManager, packageName: String): String {
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString().trim().take(USAGE_LABEL_MAX)
                .ifEmpty { packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
