package dev.phonepi.companion.messaging

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.phonepi.companion.R
import dev.phonepi.companion.receiver.MessageActionReceiver
import dev.phonepi.companion.ui.MessageReplyActivity
import dev.phonepi.companion.util.NotificationHelper

object MessageNotificationHelper {
    fun post(
        context: Context,
        messageId: String,
        title: String,
        body: String,
        responseOptions: List<String>,
        priorityHigh: Boolean,
    ) {
        NotificationHelper.ensureChannels(context)
        val channel = if (priorityHigh) {
            NotificationHelper.CHANNEL_HIGH
        } else {
            NotificationHelper.CHANNEL_MESSAGES
        }

        val openIntent = MessageReplyActivity.intent(context, messageId)
        val contentPending = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .setPriority(
                if (priorityHigh) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setCategory(Notification.CATEGORY_MESSAGE)

        val options = responseOptions.ifEmpty { listOf("Yes", "No") }
        options.forEachIndexed { index, label ->
            val actionIntent = Intent(context, MessageActionReceiver::class.java).apply {
                action = MessageActionReceiver.ACTION_RESPOND
                putExtra(MessageActionReceiver.EXTRA_MESSAGE_ID, messageId)
                putExtra(MessageActionReceiver.EXTRA_RESPONSE, label)
                putExtra(MessageActionReceiver.EXTRA_RESPONSE_INDEX, index)
            }
            val actionPending = PendingIntent.getBroadcast(
                context,
                (messageId + ":$index").hashCode(),
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, label, actionPending)
        }

        val dismissIntent = Intent(context, MessageActionReceiver::class.java).apply {
            action = MessageActionReceiver.ACTION_DISMISS
            putExtra(MessageActionReceiver.EXTRA_MESSAGE_ID, messageId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            (messageId + ":dismiss").hashCode(),
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, "Dismiss", dismissPending)

        context.getSystemService(android.app.NotificationManager::class.java)
            .notify(messageId.hashCode(), builder.build())
    }

    fun cancel(context: Context, messageId: String) {
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(messageId.hashCode())
    }
}
