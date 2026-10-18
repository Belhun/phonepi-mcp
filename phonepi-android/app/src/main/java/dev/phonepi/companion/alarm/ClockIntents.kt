package dev.phonepi.companion.alarm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * Standard Clock intents only. Callers never supply an action string.
 * SKIP_UI is always true for set alarm and set timer.
 */
object ClockIntents {
    const val GOOGLE_CLOCK = "com.google.android.deskclock"

    const val SET_ALARM = "ACTION_SET_ALARM"
    const val SET_TIMER = "ACTION_SET_TIMER"
    const val DISMISS_ALARM = "ACTION_DISMISS_ALARM"
    const val DISMISS_TIMER = "ACTION_DISMISS_TIMER"

    fun setAlarm(request: SetAlarmRequest): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, request.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            request.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (request.days.isNotEmpty()) {
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(request.days))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun setTimer(request: SetTimerRequest): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, request.seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            request.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun dismissAlarm(request: DismissAlarmRequest): Intent =
        Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            if (request.search == "label") {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                putExtra(AlarmClock.EXTRA_MESSAGE, request.message)
            } else {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                putExtra(AlarmClock.EXTRA_HOUR, request.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
                putExtra(AlarmClock.EXTRA_IS_PM, request.hour >= 12)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun dismissTimer(request: DismissTimerRequest): Intent =
        Intent(AlarmClock.ACTION_DISMISS_TIMER).apply {
            request.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** True when some activity can handle this intent, preferring Google Clock. */
    fun supported(context: Context, intent: Intent): Boolean {
        val pm = context.packageManager
        val google = Intent(intent).setPackage(GOOGLE_CLOCK)
        if (google.resolveActivity(pm) != null) return true
        return intent.resolveActivity(pm) != null
    }

    /** Point at Google Clock when it is the handler. Otherwise leave the intent implicit. */
    fun preferInstalledClock(context: Context, intent: Intent): Intent {
        val google = Intent(intent).setPackage(GOOGLE_CLOCK)
        return if (google.resolveActivity(context.packageManager) != null) google else intent
    }

    fun unsupportedMessage(action: String): String =
        "This Clock app does not support $action. PhonePi cannot do it. Open the Clock app and change it there."
}
