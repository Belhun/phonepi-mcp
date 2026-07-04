package dev.phonepi.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.tools.ClockTimer
import dev.phonepi.companion.tools.TimerFallback
import dev.phonepi.companion.tools.TimerHandoffCoordinator
import dev.phonepi.companion.tools.TimerHandoffResult
import dev.phonepi.companion.tools.TimerVia

/**
 * Foreground trampoline: waits until resumed, then fires the adb-style SET_TIMER intent.
 */
class TimerBridgeActivity : ComponentActivity() {

    private var handled = false

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true

        val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "PhonePi timer"
        val skipUi = intent.getBooleanExtra(EXTRA_SKIP_UI, false)
        val token = intent.getStringExtra(EXTRA_HANDOFF_TOKEN)

        if (seconds < 1) {
            completeHandoff(token, TimerHandoffResult(TimerVia.ALARM_MANAGER, "Invalid seconds"))
            finish()
            return
        }

        // Defer until the activity window is active (matches adb launching from a live context).
        window.decorView.post {
            val launch = ClockTimer.launch(this, seconds, label, skipUi)
            val result = if (launch.success) {
                Log.d(TAG, "Clock handoff ok (target=${launch.usedTarget}, skipUi=${launch.usedSkipUi})")
                ActivityLog.logTimerHandoff(
                    "Clock intent accepted (target=${launch.usedTarget}, skipUi=${launch.usedSkipUi})",
                    "success",
                )
                TimerHandoffResult(TimerVia.CLOCK_APP)
            } else {
                Log.w(TAG, "Clock handoff failed: ${launch.failureReason}")
                TimerFallback.schedule(this, seconds, label)
                ActivityLog.logTimerHandoff(
                    "Clock failed (${launch.failureReason}); scheduled PhonePi notification",
                    "warn",
                )
                TimerHandoffResult(TimerVia.ALARM_MANAGER, launch.failureReason)
            }
            completeHandoff(token, result)
            finish()
        }
    }

    private fun completeHandoff(token: String?, result: TimerHandoffResult) {
        if (token != null) {
            TimerHandoffCoordinator.complete(token, result)
        }
    }

    companion object {
        private const val TAG = "TimerBridge"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SKIP_UI = "skipUi"
        const val EXTRA_HANDOFF_TOKEN = "handoffToken"

        fun intent(
            context: Context,
            seconds: Int,
            label: String,
            skipUi: Boolean,
            handoffToken: String,
        ): Intent =
            Intent(context, TimerBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SECONDS, seconds)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_SKIP_UI, skipUi)
                putExtra(EXTRA_HANDOFF_TOKEN, handoffToken)
            }
    }
}
