package dev.phonepi.clock.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Wall-clock alarm math.
 *
 * Days are a bitmask, not Calendar.DAY_OF_WEEK numbers:
 * Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64.
 * Mask 0 is a one-time alarm (the next time that wall clock occurs).
 *
 * A spring-forward gap rings at the first valid minute after the gap.
 * A fall-back repeated hour rings once, on the earlier offset. If that
 * earlier instant has already passed, the later copy of the hour is not used.
 */
object DayMask {
    const val MONDAY = 1 shl 0
    const val TUESDAY = 1 shl 1
    const val WEDNESDAY = 1 shl 2
    const val THURSDAY = 1 shl 3
    const val FRIDAY = 1 shl 4
    const val SATURDAY = 1 shl 5
    const val SUNDAY = 1 shl 6
    const val WEEKDAYS = MONDAY or TUESDAY or WEDNESDAY or THURSDAY or FRIDAY
    const val WEEKENDS = SATURDAY or SUNDAY
    const val ALL = WEEKDAYS or WEEKENDS

    fun bit(day: DayOfWeek): Int = when (day) {
        DayOfWeek.MONDAY -> MONDAY
        DayOfWeek.TUESDAY -> TUESDAY
        DayOfWeek.WEDNESDAY -> WEDNESDAY
        DayOfWeek.THURSDAY -> THURSDAY
        DayOfWeek.FRIDAY -> FRIDAY
        DayOfWeek.SATURDAY -> SATURDAY
        DayOfWeek.SUNDAY -> SUNDAY
    }

    fun keyword(word: String): Int? = when (word.trim().lowercase()) {
        "weekdays" -> WEEKDAYS
        "weekends" -> WEEKENDS
        "daily", "everyday", "every day" -> ALL
        "mon", "monday" -> MONDAY
        "tue", "tues", "tuesday" -> TUESDAY
        "wed", "wednesday" -> WEDNESDAY
        "thu", "thur", "thurs", "thursday" -> THURSDAY
        "fri", "friday" -> FRIDAY
        "sat", "saturday" -> SATURDAY
        "sun", "sunday" -> SUNDAY
        else -> null
    }

    /** Names, a keyword, or a 0..127 mask. Null when the value is not a day spec. */
    fun parse(value: Any?): Int? {
        when (value) {
            null -> return 0
            is Int -> return value.takeIf { it in 0..ALL }
            is Long -> return value.toInt().takeIf { it in 0..ALL }
            is String -> {
                val text = value.trim()
                if (text.isEmpty()) return 0
                text.toIntOrNull()?.let { return it.takeIf { n -> n in 0..ALL } }
                keyword(text)?.let { return it }
                val parts = text.split(',', ' ', '+').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) return 0
                var mask = 0
                for (part in parts) {
                    val bit = keyword(part) ?: return null
                    mask = mask or bit
                }
                return mask
            }
            is Iterable<*> -> {
                var mask = 0
                for (item in value) {
                    val bit = parse(item) ?: return null
                    mask = mask or bit
                }
                return mask
            }
            else -> return null
        }
    }
}

fun nextRing(
    hour: Int,
    minute: Int,
    daysMask: Int,
    skipNext: Boolean,
    now: ZonedDateTime,
): ZonedDateTime? {
    if (hour !in 0..23 || minute !in 0..59) return null
    if (daysMask == 0 && skipNext) return null
    val upcoming = upcomingRings(hour, minute, daysMask, now, limit = if (skipNext) 2 else 1)
    return if (skipNext) upcoming.getOrNull(1) else upcoming.firstOrNull()
}

private fun upcomingRings(
    hour: Int,
    minute: Int,
    daysMask: Int,
    now: ZonedDateTime,
    limit: Int,
): List<ZonedDateTime> {
    val zone = now.zone
    val found = ArrayList<ZonedDateTime>(limit)
    var date = now.toLocalDate()
    val last = date.plusDays(21)
    while (!date.isAfter(last) && found.size < limit) {
        if (daysMask == 0 || (daysMask and DayMask.bit(date.dayOfWeek)) != 0) {
            val resolved = resolveWallTime(date, hour, minute, zone)
            if (resolved != null && resolved.toInstant().isAfter(now.toInstant())) {
                found.add(resolved)
            }
        }
        date = date.plusDays(1)
    }
    return found
}

/**
 * One zoned time for this wall clock, or null when the local time cannot be placed.
 * Overlap: the earlier offset. Gap: the first valid local time after the gap.
 */
fun resolveWallTime(
    date: LocalDate,
    hour: Int,
    minute: Int,
    zone: ZoneId,
): ZonedDateTime? {
    val local = LocalDateTime.of(date, LocalTime.of(hour, minute))
    val rules = zone.rules
    val offsets = rules.getValidOffsets(local)
    if (offsets.size == 1) {
        return ZonedDateTime.ofStrict(local, offsets[0], zone)
    }
    if (offsets.size > 1) {
        val earlier = offsets.maxBy { it.totalSeconds }
        return ZonedDateTime.ofStrict(local, earlier, zone)
    }
    val transition = rules.getTransition(local) ?: return null
    val after = transition.dateTimeAfter
    return ZonedDateTime.ofLocal(after, zone, transition.offsetAfter)
}
