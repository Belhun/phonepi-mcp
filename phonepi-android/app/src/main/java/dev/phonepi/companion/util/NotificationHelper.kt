package dev.phonepi.companion.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.phonepi.companion.R

object NotificationHelper {
    const val CHANNEL_DEFAULT = "phonepi_default"
    const val CHANNEL_HIGH = "phonepi_high"
    const val CHANNEL_URGENT = "phonepi_urgent_messages"
    const val CHANNEL_TIMER = "phonepi_timer"
    const val CHANNEL_MESSAGES = "phonepi_messages"
    const val CHANNEL_FIND_PHONE = "phonepi_find_phone"
    const val CHANNEL_NAVIGATION = "phonepi_navigation"
    const val CHANNEL_SEND_TEXT = "phonepi_send_text"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CHANNEL_DEFAULT, "PhonePi", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_HIGH, "PhonePi urgent", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(
                CHANNEL_URGENT,
                "PhonePi urgent messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description =
                    "High-priority PhonePi messages. Allow this channel through Do Not Disturb yourself; PhonePi does not change DND."
            },
            NotificationChannel(CHANNEL_TIMER, "PhonePi timers", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_MESSAGES, "PhonePi messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_FIND_PHONE, "PhonePi find phone", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_NAVIGATION, "PhonePi navigation", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_SEND_TEXT, "PhonePi texts", NotificationManager.IMPORTANCE_HIGH),
        )
        channels.forEach { manager.createNotificationChannel(it) }
    }

    fun post(
        context: Context,
        id: Int,
        channelId: String,
        title: String,
        body: String,
        priorityHigh: Boolean = false,
    ) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                if (priorityHigh) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(id, notification)
    }
}
