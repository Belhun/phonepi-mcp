package dev.phonepi.companion.nav

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.phonepi.companion.R
import dev.phonepi.companion.ui.NavigateBridgeActivity

data class NavigationLaunchResult(
    val started: Boolean,
    val notificationPosted: Boolean,
    val method: String,
    val uri: String,
    val grantNeeded: String? = null,
    val error: String? = null,
)

/**
 * Starts Google Maps navigation. Never accepts a caller-supplied action or
 * package: the only intents are the allowlisted URI candidates.
 *
 * Android 10+ background activity limits: try startActivity from the already
 * running connectedDevice foreground service, then fall back to a full-screen
 * notification that opens [NavigateBridgeActivity] (which then starts Maps).
 */
object NavigationLauncher {
    const val CHANNEL_ID = "phonepi_navigation"
    private const val NOTIF_ID = 42
    private const val PENDING_REQ = 4201

    fun launch(context: Context, plan: NavigationPlan): NavigationLaunchResult {
        if (!mapsInstalled(context)) {
            return NavigationLaunchResult(
                started = false,
                notificationPosted = false,
                method = "none",
                uri = NavigationUris.candidates(plan).first(),
                error = "Google Maps is not installed",
            )
        }
        val uris = NavigationUris.candidates(plan)
        val startedUri = tryStart(context, uris)
        if (startedUri != null) {
            return NavigationLaunchResult(
                started = true,
                notificationPosted = false,
                method = "activity",
                uri = startedUri,
            )
        }
        val grantNeeded = fullScreenGrantNeeded(context)
        val posted = postFullScreenNotification(context, plan, uris.first())
        if (posted) {
            return NavigationLaunchResult(
                started = false,
                notificationPosted = true,
                method = "full_screen_notification",
                uri = uris.first(),
                grantNeeded = grantNeeded,
            )
        }
        return NavigationLaunchResult(
            started = false,
            notificationPosted = false,
            method = "none",
            uri = uris.first(),
            grantNeeded = grantNeeded ?: "notifications",
            error = "Android blocked starting Maps from the background. " +
                "Grant notifications and full-screen intents, then tap the PhonePi navigation notification.",
        )
    }

    fun mapsIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(NavigationUris.MAPS_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun tryStart(context: Context, uris: List<String>): String? {
        for (uri in uris) {
            val intent = mapsIntent(uri)
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                return uri
            } catch (_: Exception) {
                continue
            }
        }
        for (uri in uris) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return uri
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun postFullScreenNotification(
        context: Context,
        plan: NavigationPlan,
        uri: String,
    ): Boolean {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val bridge = NavigateBridgeActivity.intent(context, plan.query, plan.sourceUrl, uri)
        val options = backgroundStartOptions()
        val pending = PendingIntent.getActivity(
            context,
            PENDING_REQ,
            bridge,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Start navigation")
            .setContentText(plan.display)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Open Google Maps to ${plan.display}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
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
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }.toBundle()
    }

    private fun fullScreenGrantNeeded(context: Context): String? {
        if (Build.VERSION.SDK_INT < 34) return null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return if (!manager.canUseFullScreenIntent()) {
            "full-screen intents (Settings → Apps → PhonePi → Full screen notifications)"
        } else {
            null
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navigation",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Starts Google Maps navigation from PhonePi"
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun mapsInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    NavigationUris.MAPS_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(NavigationUris.MAPS_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
