package dev.phonepi.companion

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide count of started activities. Used to tell a trusted foreground startActivity from a silent background no-op. */
object AppForeground {
    private val started = AtomicInteger(0)

    fun isInForeground(): Boolean = started.get() > 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                started.updateAndGet { n -> (n - 1).coerceAtLeast(0) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
