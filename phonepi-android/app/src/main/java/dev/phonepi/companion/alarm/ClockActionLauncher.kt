package dev.phonepi.companion.alarm

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
import dev.phonepi.companion.ui.ClockBridgeActivity

data class ClockLaunchResult(
    val started: Boolean,
    val notificationPosted: Boolean,
    val method: String,
    val supported: Boolean,
    val overlayGranted: Boolean,
    val inForeground: Boolean,
    val action: String,
    val grantNeeded: String? = null,
    val error: String? = null,
)

/**
 * Hands a Clock intent to the system only when a background start can be
 * trusted (foreground, or Display over other apps). Otherwise posts a
 * full-screen notification. An untrusted startActivity is not fired: SKIP_UI
 * can succeed with no UI, and a later tap would create a second alarm.
 */
object ClockActionLauncher {
    const val CHANNEL_ID = "phonepi_clock"

    fun setAlarm(context: Context, request: SetAlarmRequest): ClockLaunchResult {
        val intent = ClockIntents.preferInstalledClock(context, ClockIntents.setAlarm(request))
        val bridge = ClockBridgeActivity.setAlarm(context, request)
        return handoff(
            context = context,
            action = ClockIntents.SET_ALARM,
            intent = intent,
            bridge = bridge,
            title = "Set alarm",
            body = alarmBody(request),
            notifId = 61,
            onTrusted = {
                AlarmRecordStore.add(context, recordFrom(request))
            },
        )
    }

    fun setTimer(context: Context, request: SetTimerRequest): ClockLaunchResult {
        val intent = ClockIntents.preferInstalledClock(context, ClockIntents.setTimer(request))
        return handoff(
            context = context,
            action = ClockIntents.SET_TIMER,
            intent = intent,
            bridge = ClockBridgeActivity.setTimer(context, request),
            title = "Start timer",
            body = timerBody(request),
            notifId = 62,
            onTrusted = {},
        )
    }

    fun dismissAlarm(context: Context, request: DismissAlarmRequest): ClockLaunchResult {
        val intent = ClockIntents.preferInstalledClock(context, ClockIntents.dismissAlarm(request))
        val body = if (request.search == "label") {
            "Dismiss alarm labeled ${request.message}"
        } else {
            "Dismiss alarm at ${formatClock(request.hour, request.minute)}"
        }
        return handoff(
            context = context,
            action = ClockIntents.DISMISS_ALARM,
            intent = intent,
            bridge = ClockBridgeActivity.dismissAlarm(context, request),
            title = "Dismiss alarm",
            body = body,
            notifId = 63,
            onTrusted = {},
        )
    }

    fun dismissTimer(context: Context, request: DismissTimerRequest): ClockLaunchResult {
        val intent = ClockIntents.preferInstalledClock(context, ClockIntents.dismissTimer(request))
        val body = request.message?.let { "Dismiss timer $it" } ?: "Dismiss the Clock timer"
        return handoff(
            context = context,
            action = ClockIntents.DISMISS_TIMER,
            intent = intent,
            bridge = ClockBridgeActivity.dismissTimer(context, request),
            title = "Dismiss timer",
            body = body,
            notifId = 64,
            onTrusted = {},
        )
    }

    fun recordFrom(request: SetAlarmRequest, createdAt: Long = System.currentTimeMillis()) =
        PhonePiAlarmRecord(
            hour = request.hour,
            minute = request.minute,
            label = request.message.orEmpty(),
            days = request.days,
            createdAt = createdAt,
        )

    private fun handoff(
        context: Context,
        action: String,
        intent: Intent,
        bridge: Intent,
        title: String,
        body: String,
        notifId: Int,
        onTrusted: () -> Unit,
    ): ClockLaunchResult {
        val overlayGranted = Settings.canDrawOverlays(context)
        val inForeground = AppForeground.isInForeground()
        val policy = NavigationLaunchPolicy.decide(overlayGranted, inForeground)
        if (!ClockIntents.supported(context, intent)) {
            return ClockLaunchResult(
                started = false,
                notificationPosted = false,
                method = NavigationLaunchPolicy.METHOD_NONE,
                supported = false,
                overlayGranted = overlayGranted,
                inForeground = inForeground,
                action = action,
                error = ClockIntents.unsupportedMessage(action),
            )
        }

        if (policy.trustActivityStart) {
            try {
                context.startActivity(intent)
                onTrusted()
                return ClockLaunchResult(
                    started = true,
                    notificationPosted = false,
                    method = policy.methodIfTrustedStart,
                    supported = true,
                    overlayGranted = overlayGranted,
                    inForeground = inForeground,
                    action = action,
                )
            } catch (e: Exception) {
                val posted = postNotification(context, bridge, title, body, notifId)
                if (posted) {
                    return ClockLaunchResult(
                        started = false,
                        notificationPosted = true,
                        method = NavigationLaunchPolicy.METHOD_FULL_SCREEN_NOTIFICATION,
                        supported = true,
                        overlayGranted = overlayGranted,
                        inForeground = inForeground,
                        action = action,
                        grantNeeded = grantNeeded(context, overlayGranted, usedNotification = true),
                        error = null,
                    )
                }
                return ClockLaunchResult(
                    started = false,
                    notificationPosted = false,
                    method = NavigationLaunchPolicy.METHOD_NONE,
                    supported = true,
                    overlayGranted = overlayGranted,
                    inForeground = inForeground,
                    action = action,
                    grantNeeded = grantNeeded(context, overlayGranted, usedNotification = true),
                    error = e.message ?: "Clock did not start",
                )
            }
        }

        val posted = postNotification(context, bridge, title, body, notifId)
        if (!posted) {
            return ClockLaunchResult(
                started = false,
                notificationPosted = false,
                method = NavigationLaunchPolicy.METHOD_NONE,
                supported = true,
                overlayGranted = overlayGranted,
                inForeground = inForeground,
                action = action,
                grantNeeded = grantNeeded(context, overlayGranted, usedNotification = true),
                error = "Android blocked starting Clock from the background. " +
                    "Grant Display over other apps, or notifications and full-screen intents, then retry.",
            )
        }
        return ClockLaunchResult(
            started = false,
            notificationPosted = true,
            method = NavigationLaunchPolicy.METHOD_FULL_SCREEN_NOTIFICATION,
            supported = true,
            overlayGranted = overlayGranted,
            inForeground = inForeground,
            action = action,
            grantNeeded = grantNeeded(context, overlayGranted, usedNotification = true),
        )
    }

    private fun grantNeeded(
        context: Context,
        overlayGranted: Boolean,
        usedNotification: Boolean,
    ): String? = NavigationLaunchPolicy.grantNeeded(
        overlayGranted,
        fullScreenIntentOk(context),
        usedNotification,
    )

    private fun fullScreenIntentOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    private fun postNotification(
        context: Context,
        bridge: Intent,
        title: String,
        body: String,
        notifId: Int,
    ): Boolean {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val options = backgroundStartOptions()
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            bridge,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(notifId, notification)
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

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Clock",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Sets or dismisses Clock alarms and timers from PhonePi"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun formatClock(hour: Int, minute: Int): String {
        val period = if (hour < 12) "AM" else "PM"
        val h = when (val mod = hour % 12) {
            0 -> 12
            else -> mod
        }
        return "%d:%02d %s".format(h, minute, period)
    }

    fun alarmBody(request: SetAlarmRequest): String {
        val whenText = formatClock(request.hour, request.minute)
        val label = request.message?.let { " $it" }.orEmpty()
        val days = if (request.days.isEmpty()) "" else " (${AlarmDays.names(request.days).joinToString(", ")})"
        return "$whenText$label$days"
    }

    fun timerBody(request: SetTimerRequest): String {
        val label = request.message?.let { " $it" }.orEmpty()
        return "${formatDuration(request.seconds)}$label"
    }

    fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remain = seconds % 60
        return when {
            hours > 0 && minutes == 0 && remain == 0 -> "${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 && remain == 0 -> "$minutes min"
            minutes > 0 -> "${minutes}m ${remain}s"
            else -> "${remain}s"
        }
    }
}
