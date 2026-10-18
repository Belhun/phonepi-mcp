package dev.phonepi.companion.tools

import android.app.AlarmManager
import android.content.Context
import dev.phonepi.companion.alarm.AlarmDays
import dev.phonepi.companion.alarm.AlarmRecordStore
import dev.phonepi.companion.alarm.AlarmRequests
import dev.phonepi.companion.alarm.ClockActionLauncher
import dev.phonepi.companion.alarm.ClockResponses
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

class SetAlarmTool(private val context: Context) : ToolHandler {
    override val name = "set_alarm"

    override suspend fun handle(params: JsonObject): JsonObject {
        val request = try {
            AlarmRequests.setAlarm(params)
        } catch (e: IllegalArgumentException) {
            return ClockResponses.validationError(e.message ?: "Invalid alarm")
        }
        val result = ClockActionLauncher.setAlarm(context, request)
        return ClockResponses.payload(result, ClockResponses.SET_ALARM_NOTE) {
            put("hour", request.hour)
            put("minute", request.minute)
            put("skipUi", true)
            request.message?.let { put("label", it) }
            ClockResponses.putDays(this, request.days)
        }
    }
}

class DismissAlarmTool(private val context: Context) : ToolHandler {
    override val name = "dismiss_alarm"

    override suspend fun handle(params: JsonObject): JsonObject {
        val request = try {
            AlarmRequests.dismissAlarm(params)
        } catch (e: IllegalArgumentException) {
            return ClockResponses.validationError(e.message ?: "Invalid dismiss")
        }
        val result = ClockActionLauncher.dismissAlarm(context, request)
        return ClockResponses.payload(result, ClockResponses.DISMISS_ALARM_NOTE) {
            put("search", request.search)
            if (request.search == "time") {
                put("hour", request.hour)
                put("minute", request.minute)
            } else {
                request.message?.let { put("label", it) }
            }
        }
    }
}

class DismissTimerTool(private val context: Context) : ToolHandler {
    override val name = "dismiss_timer"

    override suspend fun handle(params: JsonObject): JsonObject {
        val request = try {
            AlarmRequests.dismissTimer(params)
        } catch (e: IllegalArgumentException) {
            return ClockResponses.validationError(e.message ?: "Invalid dismiss")
        }
        val result = ClockActionLauncher.dismissTimer(context, request)
        return ClockResponses.payload(result, ClockResponses.DISMISS_TIMER_NOTE) {
            request.message?.let { put("label", it) }
        }
    }
}

class ListAlarmsTool(private val context: Context) : ToolHandler {
    override val name = "list_alarms"

    override suspend fun handle(@Suppress("UNUSED_PARAMETER") params: JsonObject): JsonObject {
        val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        val records = AlarmRecordStore.list(context)
        return success {
            if (next == null) {
                put("nextAlarm", JsonNull)
            } else {
                putJsonObject("nextAlarm") {
                    put("triggerAt", next.triggerTime)
                    put("triggerAtIso", Instant.ofEpochMilli(next.triggerTime).toString())
                }
            }
            putJsonArray("phonePiAlarms") {
                records.forEach { record ->
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("hour", record.hour)
                            put("minute", record.minute)
                            put("label", record.label)
                            putJsonArray("days") {
                                AlarmDays.names(record.days).forEach { add(JsonPrimitive(it)) }
                            }
                            put("createdAt", record.createdAt)
                            put("createdAtIso", Instant.ofEpochMilli(record.createdAt).toString())
                        },
                    )
                }
            }
            put(
                "note",
                "nextAlarm is AlarmManager.getNextAlarmClock() and can belong to any app. " +
                    "phonePiAlarms is only what PhonePi asked Clock to create. " +
                    "Android does not let PhonePi list or edit other Clock alarms.",
            )
        }
    }
}
