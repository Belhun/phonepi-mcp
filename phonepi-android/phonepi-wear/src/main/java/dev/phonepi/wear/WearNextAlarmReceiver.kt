package dev.phonepi.wear

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.wear.complication.NextAlarmComplicationService
import dev.phonepi.wear.tile.PhonePiTileService

class WearNextAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
        NextAlarmComplicationService.requestUpdate(context)
        PhonePiTileService.requestUpdate(context)
    }
}
