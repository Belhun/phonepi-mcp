package dev.phonepi.companion.connection

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.phonepi.companion.notify.NotificationAccess
import dev.phonepi.companion.volume.MidnightVolume
import dev.phonepi.companion.volume.VolumeController

/** Live grant checks for the six permissions health is allowed to expose. */
object PhonePermissionSnapshot {
    fun capture(context: Context): PhonePermissionReport.Snapshot {
        val app = context.applicationContext
        val notificationsGranted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val fullScreenGranted = if (Build.VERSION.SDK_INT >= 34) {
            app.getSystemService(NotificationManager::class.java)
                ?.canUseFullScreenIntent() == true
        } else {
            true
        }
        return PhonePermissionReport.fromFlags(
            mapOf(
                "overlay" to Settings.canDrawOverlays(app),
                "post_notifications" to notificationsGranted,
                "full_screen_intent" to fullScreenGranted,
                "notification_listener" to NotificationAccess.isGranted(app),
                "dnd_access" to VolumeController.hasNotificationPolicyAccess(app),
                "exact_alarms" to MidnightVolume.exactAlarmsAllowed(app),
            ),
        )
    }
}
