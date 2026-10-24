package dev.phonepi.companion.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives dev.phonepi.clock.action.SNAPSHOT from the clock app. */
class ClockSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val snapshot = intent.getStringExtra("snapshot") ?: return
        ClockSnapshotSync.publish(snapshot)
    }
}
