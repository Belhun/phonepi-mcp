package dev.phonepi.companion.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.phonepi.companion.receiver.TimerReceiver
import java.time.Instant

/** PhonePi notification when the system Clock app cannot take the timer. */
object TimerFallback {
    fun schedule(context: Context, seconds: Int, label: String): String {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val intent = Intent(context, TimerReceiver::class.java).apply {
            putExtra(TimerReceiver.EXTRA_LABEL, "$label ($seconds s)")
            putExtra(TimerReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + seconds * 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
        return Instant.ofEpochMilli(triggerAt).toString()
    }
}
