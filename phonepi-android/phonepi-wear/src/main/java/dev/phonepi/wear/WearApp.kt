package dev.phonepi.wear

import android.app.Application
import com.google.android.gms.wearable.Wearable

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            Wearable.getCapabilityClient(this).addLocalCapability(WearPaths.WEAR_CAPABILITY)
        }
    }
}
