package dev.phonepi.companion.textsend

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.phonepi.companion.R
import dev.phonepi.companion.util.NotificationHelper

object SendTextNotifications {
    fun postConfirm(context: Context, record: SendRecord) {
        NotificationHelper.ensureChannels(context)
        val body = record.message
        val notification = base(context, record.id, "Text to ${record.recipientName}", body)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, "Send", action(context, record.id, SendTextActionReceiver.ACTION_SEND))
            .addAction(0, "Cancel", action(context, record.id, SendTextActionReceiver.ACTION_CANCEL))
            .build()
        notify(context, record.id, notification)
    }

    fun postSent(context: Context, record: SendRecord, body: String) {
        NotificationHelper.ensureChannels(context)
        val notification = base(context, record.id, "Sent to ${record.recipientName}", body)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        notify(context, record.id, notification)
    }

    fun dismiss(context: Context, id: String) {
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(notificationId(id))
    }

    fun notificationId(id: String): Int = 41_000 + (id.hashCode() and 0x0FFF)

    private fun notify(context: Context, id: String, notification: Notification) {
        context.getSystemService(android.app.NotificationManager::class.java)
            .notify(notificationId(id), notification)
    }

    private fun base(
        context: Context,
        id: String,
        title: String,
        body: String,
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, NotificationHelper.CHANNEL_SEND_TEXT)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(action(context, id, SendTextActionReceiver.ACTION_OPEN))
    }

    private fun action(context: Context, id: String, action: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = Intent(context, SendTextActionReceiver::class.java).apply {
            this.action = action
            putExtra(SendTextActionReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(context, (id + action).hashCode(), intent, flags)
    }
}
