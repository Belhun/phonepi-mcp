package dev.phonepi.companion.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** System next-alarm changed. Republish Data Layer state for the watch. */
class WearNextAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
        WearStatePublisher.publish(context)
    }
}
