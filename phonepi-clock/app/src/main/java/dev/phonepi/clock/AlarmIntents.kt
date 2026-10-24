package dev.phonepi.clock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.phonepi.clock.receiver.AlarmFireReceiver
import dev.phonepi.clock.ui.MainActivity
import dev.phonepi.clock.ui.RingActivity

object AlarmIntents {
    const val ACTION_FIRE = "dev.phonepi.clock.FIRE"
    const val ACTION_PRE = "dev.phonepi.clock.PRE"
    const val ACTION_SKIP = "dev.phonepi.clock.SKIP"
    const val ACTION_SNOOZE = "dev.phonepi.clock.SNOOZE"
    const val ACTION_DISMISS = "dev.phonepi.clock.DISMISS"
    const val EXTRA_ALARM_ID = "alarmId"

    fun fire(context: Context, alarmId: String): PendingIntent =
        broadcast(context, alarmId, ACTION_FIRE, 1)

    fun pre(context: Context, alarmId: String): PendingIntent =
        broadcast(context, alarmId, ACTION_PRE, 2)

    fun skip(context: Context, alarmId: String): PendingIntent =
        broadcast(context, alarmId, ACTION_SKIP, 3)

    fun snooze(context: Context, alarmId: String): PendingIntent =
        broadcast(context, alarmId, ACTION_SNOOZE, 4)

    fun dismiss(context: Context, alarmId: String): PendingIntent =
        broadcast(context, alarmId, ACTION_DISMISS, 5)

    fun show(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, flags())
    }

    fun ring(context: Context, alarmId: String): PendingIntent {
        val intent = Intent(context, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getActivity(context, code(alarmId, 6), intent, flags())
    }

    private fun broadcast(context: Context, alarmId: String, action: String, kind: Int): PendingIntent {
        val intent = Intent(context, AlarmFireReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(context, code(alarmId, kind), intent, flags())
    }

    private fun code(alarmId: String, kind: Int): Int =
        (alarmId.hashCode() * 31 + kind) and 0x7fffffff

    private fun flags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
