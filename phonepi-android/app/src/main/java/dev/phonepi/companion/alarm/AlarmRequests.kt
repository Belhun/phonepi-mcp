package dev.phonepi.companion.alarm

import dev.phonepi.companion.tools.asIntOrNull
import dev.phonepi.companion.tools.optionalString
import dev.phonepi.companion.tools.requireInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Clock command arguments. The server checks these too; the phone checks again. */
object AlarmLimits {
    const val LABEL_MAX = 100
    const val TIMER_MAX_SECONDS = 24 * 60 * 60
}

data class SetAlarmRequest(
    val hour: Int,
    val minute: Int,
    val message: String?,
    val days: List<Int>,
)

data class SetTimerRequest(
    val seconds: Int,
    val message: String?,
)

data class DismissAlarmRequest(
    val search: String,
    val hour: Int,
    val minute: Int,
    val message: String?,
)

data class DismissTimerRequest(
    val message: String?,
)

object AlarmDays {
    private val index = mapOf(
        "sunday" to 1, "sun" to 1,
        "monday" to 2, "mon" to 2,
        "tuesday" to 3, "tue" to 3, "tues" to 3,
        "wednesday" to 4, "wed" to 4,
        "thursday" to 5, "thu" to 5, "thur" to 5, "thurs" to 5,
        "friday" to 6, "fri" to 6,
        "saturday" to 7, "sat" to 7,
    )
    private val weekdays = listOf(2, 3, 4, 5, 6)
    private val weekends = listOf(1, 7)
    private val daily = listOf(1, 2, 3, 4, 5, 6, 7)

    const val DAYS_ERROR = "days must be weekday names, weekdays, weekends, or daily"

    fun name(calendarDay: Int): String = when (calendarDay) {
        1 -> "sunday"
        2 -> "monday"
        3 -> "tuesday"
        4 -> "wednesday"
        5 -> "thursday"
        6 -> "friday"
        7 -> "saturday"
        else -> calendarDay.toString()
    }

    fun names(days: List<Int>): List<String> = days.map { name(it) }

    fun parse(value: Any?): List<Int> {
        if (value == null || value == "") return emptyList()
        return when (value) {
            is String -> expand(value) ?: throw IllegalArgumentException(DAYS_ERROR)
            is List<*> -> {
                if (value.isEmpty()) return emptyList()
                val set = sortedSetOf<Int>()
                for (item in value) {
                    when (item) {
                        is Int -> {
                            if (item !in 1..7) throw IllegalArgumentException(DAYS_ERROR)
                            set.add(item)
                        }
                        is String -> set.addAll(expand(item) ?: throw IllegalArgumentException(DAYS_ERROR))
                        else -> throw IllegalArgumentException(DAYS_ERROR)
                    }
                }
                set.toList()
            }
            else -> throw IllegalArgumentException(DAYS_ERROR)
        }
    }

    fun fromJson(params: JsonObject): List<Int> {
        val element = params["days"] ?: return emptyList()
        if (element is JsonPrimitive) {
            if (element.isString) return parse(element.contentOrNull)
            element.asIntOrNull()?.let { return parse(listOf(it)) }
            throw IllegalArgumentException(DAYS_ERROR)
        }
        if (element is JsonArray) {
            val items = element.map { child ->
                if (child !is JsonPrimitive) throw IllegalArgumentException(DAYS_ERROR)
                child.asIntOrNull() ?: child.contentOrNull
            }
            return parse(items)
        }
        throw IllegalArgumentException(DAYS_ERROR)
    }

    private fun expand(raw: String): List<Int>? {
        val token = raw.trim().lowercase()
        if (token.isEmpty()) return emptyList()
        return when (token) {
            "weekdays", "weekday" -> weekdays
            "weekends", "weekend" -> weekends
            "daily", "everyday", "every day" -> daily
            else -> index[token]?.let { listOf(it) }
        }
    }
}

object AlarmRequests {
    fun setAlarm(params: JsonObject): SetAlarmRequest {
        val hour = readHour(params, allowPeriod = true)
        val minute = readMinute(params)
        val message = readLabel(params)
        val days = AlarmDays.fromJson(params)
        return SetAlarmRequest(hour, minute, message, days)
    }

    fun setTimer(params: JsonObject): SetTimerRequest {
        val seconds = params.requireInt("seconds")
        if (seconds == null || seconds < 1 || seconds > AlarmLimits.TIMER_MAX_SECONDS) {
            throw IllegalArgumentException("seconds must be an integer from 1 to 86400")
        }
        return SetTimerRequest(seconds, readLabel(params))
    }

    fun dismissAlarm(params: JsonObject): DismissAlarmRequest {
        val search = params.optionalString("search")?.trim()?.lowercase().orEmpty()
        if (search != "time" && search != "label") {
            throw IllegalArgumentException("search must be time or label")
        }
        if (search == "time") {
            return DismissAlarmRequest(
                search = "time",
                hour = readHour(params, allowPeriod = true),
                minute = readMinute(params),
                message = null,
            )
        }
        val message = readLabel(params)
            ?: throw IllegalArgumentException("dismiss by label needs a message")
        return DismissAlarmRequest(search = "label", hour = 0, minute = 0, message = message)
    }

    fun dismissTimer(params: JsonObject): DismissTimerRequest =
        DismissTimerRequest(readLabel(params))

    private fun readHour(params: JsonObject, allowPeriod: Boolean): Int {
        val hour = params.requireInt("hour")
        if (hour == null || hour !in 0..23) {
            throw IllegalArgumentException("hour must be an integer from 0 to 23")
        }
        if (!allowPeriod) return hour
        return applyPeriod(hour, readPeriod(params))
    }

    private fun readMinute(params: JsonObject): Int {
        val minute = params.requireInt("minute") ?: params.requireInt("minutes")
        if (minute == null || minute !in 0..59) {
            throw IllegalArgumentException("minute must be an integer from 0 to 59")
        }
        return minute
    }

    private fun readPeriod(params: JsonObject): String? {
        val raw = params.optionalString("period")
            ?: params.optionalString("ampm")
            ?: params.optionalString("amPm")
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun applyPeriod(hour: Int, periodRaw: String?): Int {
        if (periodRaw == null) return hour
        val period = periodRaw.lowercase().replace(".", "")
        if (period != "am" && period != "pm") {
            throw IllegalArgumentException("period must be am or pm")
        }
        if (hour !in 1..12) {
            throw IllegalArgumentException("hour must be 1 to 12 when period is set")
        }
        return if (period == "am") {
            if (hour == 12) 0 else hour
        } else {
            if (hour == 12) 12 else hour + 12
        }
    }

    private fun readLabel(params: JsonObject): String? {
        val element = when {
            params.containsKey("message") -> params["message"]
            params.containsKey("label") -> params["label"]
            else -> return null
        } ?: return null
        if (element is JsonNull) return null
        if (element !is JsonPrimitive || !element.isString) {
            throw IllegalArgumentException("message must be a string of at most 100 characters")
        }
        val message = element.content.trim()
        if (message.isEmpty()) return null
        if (message.length > AlarmLimits.LABEL_MAX) {
            throw IllegalArgumentException("message must be at most 100 characters")
        }
        return message
    }
}
