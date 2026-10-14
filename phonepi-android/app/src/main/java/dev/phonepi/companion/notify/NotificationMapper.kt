package dev.phonepi.companion.notify

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification

/** Maps a system notification to the read-only record. Never mutates the SBN. */
object NotificationMapper {
    fun fromStatusBar(sbn: StatusBarNotification, packageManager: PackageManager): NotificationRecord {
        val extras = sbn.notification.extras
        val title = firstExtra(
            extras,
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_CONVERSATION_TITLE,
        )
        val text = firstExtra(
            extras,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        ) ?: messagingFallback(extras)
        return NotificationRecord(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = resolveAppName(sbn.packageName, packageManager, extras),
            title = ExposedField.of(title, "title"),
            text = ExposedField.of(text, "text"),
            postedAt = sbn.postTime,
            isActive = true,
            notificationId = sbn.id,
        )
    }

    private fun resolveAppName(
        packageName: String,
        packageManager: PackageManager,
        extras: android.os.Bundle,
    ): ExposedField {
        val substitute = extras.getCharSequence(Notification.EXTRA_SUBSTITUTE_APP_NAME)?.toString()
        if (!substitute.isNullOrBlank()) {
            return ExposedField.of(substitute, "appName")
        }
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            ExposedField.of(packageManager.getApplicationLabel(info)?.toString(), "appName")
        } catch (_: PackageManager.NameNotFoundException) {
            ExposedField(
                value = HIDDEN_BY_ANDROID,
                hidden = true,
                reason = "Android did not expose the app label for package $packageName",
            )
        }
    }

    private fun firstExtra(extras: android.os.Bundle, vararg keys: String): String? {
        for (key in keys) {
            val value = extras.getCharSequence(key)?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun messagingFallback(extras: android.os.Bundle): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val lines = messages.mapNotNull { item ->
            if (item is android.os.Bundle) {
                item.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
