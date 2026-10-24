package dev.phonepi.clock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ClockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        channels()
        ClockEngine.rearmAsync(this, "process")
    }

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel(CHANNEL_RING, getString(R.string.channel_ring), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(channel(CHANNEL_UPCOMING, getString(R.string.channel_upcoming), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(channel(CHANNEL_MISSED, getString(R.string.channel_missed), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(channel(CHANNEL_HEALTH, getString(R.string.channel_health), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun channel(id: String, name: String, importance: Int): NotificationChannel {
        return NotificationChannel(id, name, importance).apply {
            if (id == CHANNEL_RING) {
                setBypassDnd(true)
                setSound(null, null)
            }
        }
    }

    companion object {
        const val CHANNEL_RING = "ring"
        const val CHANNEL_UPCOMING = "upcoming"
        const val CHANNEL_MISSED = "missed"
        const val CHANNEL_HEALTH = "health"
    }
}
