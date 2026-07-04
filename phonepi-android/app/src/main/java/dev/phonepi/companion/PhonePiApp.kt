package dev.phonepi.companion

import android.app.Application
import dev.phonepi.companion.log.ActivityLog

@Suppress("unused") // Referenced from AndroidManifest.xml
class PhonePiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ActivityLog.init(this)
    }
}
