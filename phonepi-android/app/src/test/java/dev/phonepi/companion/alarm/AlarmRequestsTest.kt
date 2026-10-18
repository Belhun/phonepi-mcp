package dev.phonepi.companion.alarm

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmRequestsTest {
    @Test
    fun weekdaysWorkAlarmIsSixThirty() {
        val parsed = AlarmRequests.setAlarm(buildJsonObject {
            put("hour", 6)
            put("minute", 30)
            put("label", "Work")
            put("days", "weekdays")
            put("period", "am")
        })
        assertEquals(6, parsed.hour)
        assertEquals(30, parsed.minute)
        assertEquals("Work", parsed.message)
        assertEquals(listOf(2, 3, 4, 5, 6), parsed.days)
    }

    @Test
    fun pmAndDuplicateDaysNormalize() {
        val parsed = AlarmRequests.setAlarm(buildJsonObject {
            put("hour", 6)
            put("minute", 30)
            put("period", "PM")
            put("days", buildJsonArray {
                add("mon")
                add("monday")
                add("fri")
            })
        })
        assertEquals(18, parsed.hour)
        assertEquals(listOf(2, 6), parsed.days)
    }

    @Test
    fun noonAmIsMidnightAndDailyIsEveryDay() {
        val parsed = AlarmRequests.setAlarm(buildJsonObject {
            put("hour", 12)
            put("minute", 0)
            put("period", "am")
            put("days", "daily")
        })
        assertEquals(0, parsed.hour)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), parsed.days)
        assertNull(parsed.message)
    }

    @Test
    fun alarmBoundsRejectHourMinuteAndLabel() {
        assertFails("hour must be an integer from 0 to 23") {
            AlarmRequests.setAlarm(buildJsonObject {
                put("minute", 0)
            })
        }
        assertFails("hour must be an integer from 0 to 23") {
            AlarmRequests.setAlarm(buildJsonObject {
                put("hour", 24)
                put("minute", 0)
            })
        }
        assertFails("minute must be an integer from 0 to 59") {
            AlarmRequests.setAlarm(buildJsonObject {
                put("hour", 6)
                put("minute", 60)
            })
        }
        assertFails("message must be at most 100 characters") {
            AlarmRequests.setAlarm(buildJsonObject {
                put("hour", 6)
                put("minute", 0)
                put("message", "W".repeat(101))
            })
        }
        assertFails("days must be") {
            AlarmRequests.setAlarm(buildJsonObject {
                put("hour", 6)
                put("minute", 0)
                put("days", buildJsonArray { add("funday") })
            })
        }
        val max = AlarmRequests.setAlarm(buildJsonObject {
            put("hour", 18)
            put("minutes", 0)
            put("message", "W".repeat(100))
        })
        assertEquals(100, max.message?.length)
        assertEquals(emptyList<Int>(), max.days)
    }

    @Test
    fun timerAcceptsOneSecondThroughTwentyFourHours() {
        val min = AlarmRequests.setTimer(buildJsonObject {
            put("seconds", 1)
            put("label", "Tea")
        })
        assertEquals(1, min.seconds)
        assertEquals("Tea", min.message)

        val max = AlarmRequests.setTimer(buildJsonObject { put("seconds", 86400) })
        assertEquals(86400, max.seconds)
        assertNull(max.message)

        val fifteen = AlarmRequests.setTimer(buildJsonObject { put("seconds", 15 * 60) })
        assertEquals(900, fifteen.seconds)

        for (seconds in listOf(0, -1, 86401)) {
            assertFails("seconds must be an integer from 1 to 86400") {
                AlarmRequests.setTimer(buildJsonObject { put("seconds", seconds) })
            }
        }
    }

    @Test
    fun dismissAllowsTimeOrLabelAndRefusesAll() {
        val byTime = AlarmRequests.dismissAlarm(buildJsonObject {
            put("search", "time")
            put("hour", 6)
            put("minute", 30)
        })
        assertEquals("time", byTime.search)
        assertEquals(6, byTime.hour)
        assertEquals(30, byTime.minute)

        val evening = AlarmRequests.dismissAlarm(buildJsonObject {
            put("search", "time")
            put("hour", 6)
            put("minute", 30)
            put("period", "pm")
        })
        assertEquals(18, evening.hour)

        val byLabel = AlarmRequests.dismissAlarm(buildJsonObject {
            put("search", "label")
            put("label", "Work")
        })
        assertEquals("label", byLabel.search)
        assertEquals("Work", byLabel.message)

        assertFails("search must be time or label") {
            AlarmRequests.dismissAlarm(buildJsonObject { put("search", "all") })
        }
        assertFails("message") {
            AlarmRequests.dismissAlarm(buildJsonObject { put("search", "label") })
        }
        assertNull(AlarmRequests.dismissTimer(buildJsonObject { }).message)
        assertEquals("Tea", AlarmRequests.dismissTimer(buildJsonObject { put("message", "Tea") }).message)
    }

    @Test
    fun recordLogKeepsNewestFirstAndCaps() {
        val older = PhonePiAlarmRecord(hour = 7, minute = 0, label = "Old", createdAt = 1)
        val newer = PhonePiAlarmRecord(
            hour = 6,
            minute = 30,
            label = "Work",
            days = listOf(2, 3, 4, 5, 6),
            createdAt = 2,
        )
        val kept = AlarmRecordLog.remember(List(AlarmRecordLog.MAX_RECORDS) { older }, newer)
        assertEquals(AlarmRecordLog.MAX_RECORDS, kept.size)
        assertEquals(newer, kept.first())
        val raw = AlarmRecordLog.encode(listOf(newer))
        assertEquals(listOf(newer), AlarmRecordLog.decode(raw))
        assertEquals(emptyList<PhonePiAlarmRecord>(), AlarmRecordLog.decode("not-json"))
        assertEquals(listOf("monday", "friday"), AlarmDays.names(listOf(2, 6)))
    }

    private fun assertFails(snippet: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected failure containing $snippet")
        } catch (e: IllegalArgumentException) {
            if (!e.message.orEmpty().contains(snippet)) {
                throw AssertionError("expected [$snippet] in [${e.message}]")
            }
        }
    }
}
