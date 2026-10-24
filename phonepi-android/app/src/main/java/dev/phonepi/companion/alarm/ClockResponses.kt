package dev.phonepi.companion.alarm

import dev.phonepi.companion.tools.toolError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object ClockResponses {
    const val SET_ALARM_NOTE =
        "Clock was asked to save this alarm with SKIP_UI. Android does not confirm the alarm was saved. " +
            "PhonePi records it locally only after that handoff. list_alarms is not the Clock app's full list."

    const val SET_ALARM_WAITING =
        "Android will not start Clock from the background without Display over other apps. " +
            "A PhonePi notification is waiting. Tap it to set the alarm. started is false, and the alarm " +
            "is not recorded until that tap hands the intent to Clock."

    const val SET_TIMER_NOTE =
        "Clock was asked to start this countdown with SKIP_UI. Android does not confirm the timer is running. " +
            "PhonePi does not schedule its own countdown if Clock cannot take the timer."

    const val SET_TIMER_WAITING =
        "Android will not start Clock from the background without Display over other apps. " +
            "A PhonePi notification is waiting. Tap it to start the timer. started is false until that tap."

    const val DISMISS_ALARM_NOTE =
        "PhonePi asked the Clock app to dismiss a matching alarm (ACTION_DISMISS_ALARM, search by time or label). " +
            "Third-party apps cannot delete arbitrary Clock alarms. started true means the intent was handed to Clock, " +
            "not that the alarm is gone. If Clock ignores the intent, the alarm stays."

    const val DISMISS_TIMER_NOTE =
        "PhonePi asked the Clock app to dismiss a timer (ACTION_DISMISS_TIMER). " +
            "Third-party apps cannot cancel an arbitrary Clock timer. started true means the intent was handed to Clock, " +
            "not that the timer stopped. Older Clock apps do not implement this action."

    const val DISMISS_WAITING =
        "Android will not start Clock from the background without Display over other apps. " +
            "A PhonePi notification is waiting. Tap it to send the dismiss intent. " +
            "started is false, and a tap still does not prove Clock removed anything."

    fun payload(
        result: ClockLaunchResult,
        note: String,
        fields: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        if (result.error != null && !result.started && !result.notificationPosted) {
            return buildJsonObject {
                put("status", "error")
                put("started", false)
                put("supported", result.supported)
                put("method", result.method)
                put("action", result.action)
                put("error", result.error)
                result.grantNeeded?.let { put("grantNeeded", it) }
            }
        }
        val usedNote = when {
            result.started -> note
            result.notificationPosted && result.action == ClockIntents.SET_ALARM -> SET_ALARM_WAITING
            result.notificationPosted && result.action == ClockIntents.SET_TIMER -> SET_TIMER_WAITING
            result.notificationPosted -> DISMISS_WAITING
            else -> note
        }
        return buildJsonObject {
            put("status", "success")
            put("started", result.started)
            put("supported", result.supported)
            put("notificationPosted", result.notificationPosted)
            put("method", result.method)
            put("action", result.action)
            put("overlayGranted", result.overlayGranted)
            put("inForeground", result.inForeground)
            result.grantNeeded?.let { put("grantNeeded", it) }
            put("note", usedNote)
            fields()
        }
    }

    fun validationError(message: String): JsonObject = toolError(message)

    fun putDays(builder: JsonObjectBuilder, days: List<Int>) {
        builder.putJsonArray("days") {
            AlarmDays.names(days).forEach { add(JsonPrimitive(it)) }
        }
    }
}
