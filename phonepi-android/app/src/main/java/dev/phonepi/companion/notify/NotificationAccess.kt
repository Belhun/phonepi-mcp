package dev.phonepi.companion.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object NotificationAccess {
    fun component(context: Context): ComponentName =
        ComponentName(context, PhonePiNotificationListener::class.java)

    fun isGranted(context: Context): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        if (enabled.contains(context.packageName)) return true
        val listed = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val mine = component(context)
        return listed.split(":").any { entry ->
            ComponentName.unflattenFromString(entry) == mine
        }
    }

    fun settingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component(context).flattenToString(),
                )
            }
            if (detail.resolveActivity(context.packageManager) != null) {
                return detail
            }
        }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
