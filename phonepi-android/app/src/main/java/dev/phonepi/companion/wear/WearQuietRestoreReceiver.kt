package dev.phonepi.companion.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WearQuietRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WearQuietController.restore(context)
    }
}
