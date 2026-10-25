package dev.phonepi.companion.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.companion.privacy.PhonePiQuiet

class WearQuietRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PhonePiQuiet.restore(context)
    }
}
