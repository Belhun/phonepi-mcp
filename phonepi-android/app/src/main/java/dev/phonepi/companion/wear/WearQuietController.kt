package dev.phonepi.companion.wear

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Phone-side quiet. INTEGRATION: cursor/phonepi-notif-actions may replace this
 * body; keep the [WearInboundActions.quietForMinutes] signature.
 *
 * Uses NotificationManager interruption filter (priority). Alarms still fire.
 * Needs ACCESS_NOTIFICATION_POLICY (Do Not Disturb access) already declared
 * for volume tools.
 */
object WearQuietController {
    private const val PREFS = "phonepi_wear_quiet"
    private const val KEY_PREV = "prev_filter"
    private const val KEY_UNTIL = "until_ms"
    private const val RESTORE_REQUEST = 4401

    fun quietFor(context: Context, minutes: Int): WearActionResult {
        val valid = WearProtocol.validateQuietMinutes(minutes)
            ?: return WearActionResult(false, "minutes must be 1..720")
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
            ?: return WearActionResult(false, "notification manager missing")
        if (!nm.isNotificationPolicyAccessGranted) {
            return WearActionResult(false, "grantNeeded:dnd_access")
        }
        val previous = nm.currentInterruptionFilter
        val until = System.currentTimeMillis() + valid * 60_000L
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PREV, previous)
            .putLong(KEY_UNTIL, until)
            .apply()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        scheduleRestore(app, until)
        return WearActionResult(true, "quiet ${valid}m")
    }

    fun restore(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getInt(KEY_PREV, NotificationManager.INTERRUPTION_FILTER_ALL)
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(previous)
        }
        prefs.edit().clear().apply()
    }

    private fun scheduleRestore(context: Context, untilMs: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = restorePending(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, untilMs, pending)
            return
        }
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, untilMs, pending)
    }

    private fun restorePending(context: Context): PendingIntent {
        val intent = Intent(context, WearQuietRestoreReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            RESTORE_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
