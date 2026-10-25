package dev.phonepi.companion.wear

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats [AlarmManager.getNextAlarmClock] trigger times for the watch. */
object NextAlarmFormat {
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayTimeFmt = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

    fun shortText(
        triggerAtMillis: Long?,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (triggerAtMillis == null || triggerAtMillis <= 0L) return "No alarm"
        val whenZdt = Instant.ofEpochMilli(triggerAtMillis).atZone(zone)
        val nowZdt = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val time = whenZdt.format(timeFmt)
        val whenDay = whenZdt.toLocalDate()
        val today = nowZdt.toLocalDate()
        return when (whenDay) {
            today -> time
            today.plusDays(1) -> "Tom $time"
            else -> whenZdt.format(dayTimeFmt)
        }
    }

    fun longText(
        triggerAtMillis: Long?,
        presenceLabel: String?,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val alarm = shortText(triggerAtMillis, nowMillis, zone)
        val presence = presenceLabel?.trim().orEmpty()
        return if (presence.isEmpty()) alarm else "$alarm · $presence"
    }
}
