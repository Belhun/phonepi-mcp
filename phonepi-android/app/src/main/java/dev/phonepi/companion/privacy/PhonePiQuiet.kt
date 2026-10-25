package dev.phonepi.companion.privacy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.phonepi.companion.wear.WearActionResult
import dev.phonepi.companion.wear.WearProtocol
import dev.phonepi.companion.wear.WearQuietRestoreReceiver

/**
 * In-app PhonePi quiet. Holds PhonePi's own non-urgent notifications.
 * Presence reports "quiet". Does not change system Do Not Disturb.
 */
object PhonePiQuiet {
    private const val PREFS = "phonepi_quiet"
    private const val KEY_UNTIL = "until_ms"
    private const val RESTORE_REQUEST = 4401

    fun quietFor(context: Context, minutes: Int): WearActionResult {
        val valid = WearProtocol.validateQuietMinutes(minutes)
            ?: return WearActionResult(false, "minutes must be 1..720")
        val app = context.applicationContext
        val until = System.currentTimeMillis() + valid * 60_000L
        prefs(app).edit().putLong(KEY_UNTIL, until).apply()
        scheduleRestore(app, until)
        return WearActionResult(true, "quiet ${valid}m")
    }

    fun isActive(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = untilMs(context)
        if (until <= 0L) return false
        if (nowMs >= until) {
            clear(context)
            return false
        }
        return true
    }

    fun untilMs(context: Context): Long =
        prefs(context).getLong(KEY_UNTIL, 0L)

    fun restore(context: Context) {
        clear(context)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
