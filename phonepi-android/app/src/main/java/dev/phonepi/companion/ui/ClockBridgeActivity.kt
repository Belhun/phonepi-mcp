package dev.phonepi.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.phonepi.companion.alarm.AlarmRecordStore
import dev.phonepi.companion.alarm.ClockActionLauncher
import dev.phonepi.companion.alarm.ClockIntents
import dev.phonepi.companion.alarm.DismissAlarmRequest
import dev.phonepi.companion.alarm.DismissTimerRequest
import dev.phonepi.companion.alarm.SetAlarmRequest
import dev.phonepi.companion.alarm.SetTimerRequest
import dev.phonepi.companion.log.ActivityLog

/**
 * Trampoline from the clock full-screen notification. Running as an activity
 * is allowed to start the Clock intent. This builds that intent itself.
 */
class ClockBridgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val clock = clockIntent()
        if (clock == null) {
            ActivityLog.logClock("Clock notification had no action ($kind)", "error")
            finish()
            return
        }
        val targeted = ClockIntents.preferInstalledClock(this, clock)
        if (!ClockIntents.supported(this, targeted)) {
            ActivityLog.logClock(
                ClockIntents.unsupportedMessage(actionName(kind)),
                "error",
            )
            finish()
            return
        }
        try {
            startActivity(targeted)
            if (kind == KIND_SET_ALARM) {
                AlarmRecordStore.add(this, ClockActionLauncher.recordFrom(alarmRequest()))
            }
            ActivityLog.logClock("Clock intent handed off from notification ($kind)", "success")
        } catch (e: Exception) {
            ActivityLog.logClock(
                "Clock did not start from notification ($kind): ${e.message}",
                "error",
            )
        }
        finish()
    }

    private fun clockIntent(): Intent? = when (intent.getStringExtra(EXTRA_KIND)) {
        KIND_SET_ALARM -> ClockIntents.setAlarm(alarmRequest())
        KIND_SET_TIMER -> ClockIntents.setTimer(
            SetTimerRequest(
                seconds = intent.getIntExtra(EXTRA_SECONDS, 0),
                message = intent.getStringExtra(EXTRA_MESSAGE),
            ),
        )
        KIND_DISMISS_ALARM -> ClockIntents.dismissAlarm(
            DismissAlarmRequest(
                search = intent.getStringExtra(EXTRA_SEARCH) ?: "time",
                hour = intent.getIntExtra(EXTRA_HOUR, 0),
                minute = intent.getIntExtra(EXTRA_MINUTE, 0),
                message = intent.getStringExtra(EXTRA_MESSAGE),
            ),
        )
        KIND_DISMISS_TIMER -> ClockIntents.dismissTimer(
            DismissTimerRequest(intent.getStringExtra(EXTRA_MESSAGE)),
        )
        else -> null
    }

    private fun alarmRequest(): SetAlarmRequest = SetAlarmRequest(
        hour = intent.getIntExtra(EXTRA_HOUR, 0),
        minute = intent.getIntExtra(EXTRA_MINUTE, 0),
        message = intent.getStringExtra(EXTRA_MESSAGE),
        days = intent.getIntegerArrayListExtra(EXTRA_DAYS)?.toList().orEmpty(),
    )

    companion object {
        const val EXTRA_KIND = "clock_kind"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DAYS = "days"
        const val EXTRA_SEARCH = "search"

        private const val KIND_SET_ALARM = "set_alarm"
        private const val KIND_SET_TIMER = "set_timer"
        private const val KIND_DISMISS_ALARM = "dismiss_alarm"
        private const val KIND_DISMISS_TIMER = "dismiss_timer"

        fun setAlarm(context: Context, request: SetAlarmRequest): Intent =
            base(context, KIND_SET_ALARM).apply {
                putExtra(EXTRA_HOUR, request.hour)
                putExtra(EXTRA_MINUTE, request.minute)
                putExtra(EXTRA_MESSAGE, request.message)
                putIntegerArrayListExtra(EXTRA_DAYS, ArrayList(request.days))
            }

        fun setTimer(context: Context, request: SetTimerRequest): Intent =
            base(context, KIND_SET_TIMER).apply {
                putExtra(EXTRA_SECONDS, request.seconds)
                putExtra(EXTRA_MESSAGE, request.message)
            }

        fun dismissAlarm(context: Context, request: DismissAlarmRequest): Intent =
            base(context, KIND_DISMISS_ALARM).apply {
                putExtra(EXTRA_SEARCH, request.search)
                putExtra(EXTRA_HOUR, request.hour)
                putExtra(EXTRA_MINUTE, request.minute)
                putExtra(EXTRA_MESSAGE, request.message)
            }

        fun dismissTimer(context: Context, request: DismissTimerRequest): Intent =
            base(context, KIND_DISMISS_TIMER).apply {
                putExtra(EXTRA_MESSAGE, request.message)
            }

        private fun base(context: Context, kind: String): Intent =
            Intent(context, ClockBridgeActivity::class.java).apply {
                putExtra(EXTRA_KIND, kind)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        private fun actionName(kind: String): String = when (kind) {
            KIND_SET_ALARM -> ClockIntents.SET_ALARM
            KIND_SET_TIMER -> ClockIntents.SET_TIMER
            KIND_DISMISS_ALARM -> ClockIntents.DISMISS_ALARM
            KIND_DISMISS_TIMER -> ClockIntents.DISMISS_TIMER
            else -> kind
        }
    }
}
