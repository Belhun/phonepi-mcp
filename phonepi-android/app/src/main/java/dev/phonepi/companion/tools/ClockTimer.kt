package dev.phonepi.companion.tools

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/** Launches Google Clock via the same SET_TIMER intent shape that works from adb. */
object ClockTimer {
    private const val TAG = "ClockTimer"
    private const val GOOGLE_CLOCK = "com.google.android.deskclock"
    private const val GOOGLE_CLOCK_HANDLER = "com.android.deskclock.HandleSetApiCalls"

    private enum class Target { GOOGLE_PACKAGE, GOOGLE_COMPONENT, GENERIC }

    data class LaunchResult(
        val success: Boolean,
        val usedSkipUi: Boolean = false,
        val usedTarget: String? = null,
        val failureReason: String? = null,
    )

    fun launch(context: Context, seconds: Int, label: String, skipUi: Boolean): LaunchResult {
        val fromActivity = context is Activity
        val skipUiAttempts = if (skipUi) listOf(true, false) else listOf(false)
        var lastError: String? = null

        for (skip in skipUiAttempts) {
            for (target in Target.entries) {
                val intent = buildIntent(seconds, label, skip, target, fromActivity) ?: continue
                val result = launchIntent(context, intent, skip, target.name)
                if (result.success) return result
                lastError = result.failureReason
            }
        }
        return LaunchResult(success = false, failureReason = lastError ?: "All launch attempts failed")
    }

    private fun launchIntent(
        context: Context,
        intent: Intent,
        skipUi: Boolean,
        targetLabel: String,
    ): LaunchResult {
        return try {
            context.startActivity(intent)
            Log.i(TAG, "Clock intent started (target=$targetLabel, skipUi=$skipUi)")
            LaunchResult(success = true, usedSkipUi = skipUi, usedTarget = targetLabel)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Activity not found (target=$targetLabel)", e)
            LaunchResult(success = false, failureReason = e.message ?: "Activity not found")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception launching clock (target=$targetLabel)", e)
            LaunchResult(success = false, failureReason = "Security: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed (target=$targetLabel)", e)
            LaunchResult(success = false, failureReason = e.message ?: "Launch failed")
        }
    }

    private fun buildIntent(
        seconds: Int,
        label: String,
        skipUi: Boolean,
        target: Target,
        fromActivity: Boolean,
    ): Intent? {
        if (seconds < 1) return null
        return Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            when (target) {
                Target.GOOGLE_PACKAGE -> setPackage(GOOGLE_CLOCK)
                Target.GOOGLE_COMPONENT -> setClassName(GOOGLE_CLOCK, GOOGLE_CLOCK_HANDLER)
                Target.GENERIC -> { /* implicit handler */ }
            }
            if (!fromActivity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
