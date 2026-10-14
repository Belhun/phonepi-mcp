package dev.phonepi.companion

import android.app.Application
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.notify.NotificationShare

@Suppress("unused") // Referenced from AndroidManifest.xml
class PhonePiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ActivityLog.init(this)
        NotificationShare.init(this)
        AppForeground.register(this)
    }
}
