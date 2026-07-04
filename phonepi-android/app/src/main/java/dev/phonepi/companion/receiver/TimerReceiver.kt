package dev.phonepi.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.companion.util.NotificationHelper

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Timer finished"
        NotificationHelper.post(
            context = context,
            id = intent.getIntExtra(EXTRA_NOTIF_ID, 9001),
            channelId = NotificationHelper.CHANNEL_TIMER,
            title = "Timer done",
            body = label,
            priorityHigh = true,
        )
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
