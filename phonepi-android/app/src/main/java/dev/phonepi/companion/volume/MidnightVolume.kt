package dev.phonepi.companion.volume

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import dev.phonepi.companion.receiver.MidnightVolumeReceiver
import java.time.Instant
import java.time.ZoneId

object MidnightVolume {
    private const val PREFS = "phonepi_volume"
    private const val KEY_ENABLED = "max_at_midnight"
    private const val REQUEST = 7101

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, on)
            .apply()
        if (on) schedule(context) else cancel(context)
    }

    fun nextMidnightEpochMillis(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val zoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val todayStart = zoned.toLocalDate().atStartOfDay(zone)
        return todayStart.plusDays(1).toInstant().toEpochMilli()
    }

    fun exactAlarmsAllowed(context: Context): Boolean {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarms.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun exactAlarmSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun rescheduleIfEnabled(context: Context) {
        if (enabled(context)) schedule(context)
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pending(app)
        val triggerAt = nextMidnightEpochMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(pending(app))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, MidnightVolumeReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
