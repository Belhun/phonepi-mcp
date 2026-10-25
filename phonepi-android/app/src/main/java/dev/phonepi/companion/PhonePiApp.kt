package dev.phonepi.companion

import android.app.Application
import dev.phonepi.companion.history.LocationCapture
import dev.phonepi.companion.presence.PresenceEngine
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.notify.NotificationShare
import dev.phonepi.companion.textsend.SendTextController
import dev.phonepi.companion.volume.MidnightVolume

@Suppress("unused") // Referenced from AndroidManifest.xml
class PhonePiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ActivityLog.init(this)
        NotificationShare.init(this)
        AppForeground.register(this)
        MidnightVolume.rescheduleIfEnabled(this)
        LocationCapture.refresh(this)
        PresenceEngine.nudge(this)
        SendTextController.rearm(this)
    }
}
