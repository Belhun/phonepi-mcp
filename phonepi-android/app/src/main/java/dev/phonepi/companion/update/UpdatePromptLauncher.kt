package dev.phonepi.companion.update

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dev.phonepi.companion.AppForeground
import dev.phonepi.companion.R
import dev.phonepi.companion.nav.NavigationLaunchPolicy
import dev.phonepi.companion.ui.InstallBridgeActivity

/**
 * Shows the system package installer UI. Never installs silently.
 * Background: overlay startActivity is trusted; otherwise a full-screen notification.
 */
object UpdatePromptLauncher {
    const val CHANNEL_ID = "phonepi_update"
    private const val NOTIF_ID = 43
    private const val PENDING_REQ = 4301

    fun showInstaller(context: Context, confirmIntent: Intent): UpdatePromptResult {
        val overlayGranted = Settings.canDrawOverlays(context)
        val inForeground = AppForeground.isInForeground()
        val policy = NavigationLaunchPolicy.decide(overlayGranted, inForeground)
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        UpdatePromptStore.confirmIntent = confirmIntent

        var started = false
        if (policy.trustActivityStart) {
            started = try {
                context.startActivity(confirmIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
        val trustedStarted = policy.trustActivityStart && started
        val shouldPost = policy.postFullScreenNotification || !trustedStarted
        val posted = if (shouldPost) postFullScreenNotification(context) else false
        val method = NavigationLaunchPolicy.method(
            trustedStartSucceeded = trustedStarted,
            notificationPosted = posted,
            overlayGranted = overlayGranted,
            inForeground = inForeground,
        )
        val usedNotification = method == NavigationLaunchPolicy.METHOD_FULL_SCREEN_NOTIFICATION
        val grantNeeded = NavigationLaunchPolicy.grantNeeded(
            overlayGranted,
            fullScreenIntentOk(context),
            usedNotification = usedNotification || (!trustedStarted && posted),
        )
        if (!trustedStarted && !posted) {
            return UpdatePromptResult(
                promptShown = false,
                method = NavigationLaunchPolicy.METHOD_NONE,
                notificationPosted = false,
                overlayGranted = overlayGranted,
                inForeground = inForeground,
                grantNeeded = grantNeeded ?: "notifications",
                error = "Android blocked the installer from the background. " +
                    "Grant Display over other apps, or tap the PhonePi update notification.",
            )
        }
        return UpdatePromptResult(
            promptShown = true,
            method = method,
            notificationPosted = posted,
            overlayGranted = overlayGranted,
            inForeground = inForeground,
            grantNeeded = grantNeeded,
        )
    }

    fun fullScreenIntentOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    private fun postFullScreenNotification(context: Context): Boolean {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val pending = PendingIntent.getActivity(
            context,
            PENDING_REQ,
            InstallBridgeActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            backgroundStartOptions(),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("PhonePi update ready")
            .setContentText("Tap to install. PhonePi never installs silently.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIF_ID, notification)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun backgroundStartOptions(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < 34) return null
        return ActivityOptions.makeBasic().apply {
            @Suppress("DEPRECATION")
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }.toBundle()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Updates",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Asks you to install a PhonePi APK from your PC"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        manager.createNotificationChannel(channel)
    }
}
