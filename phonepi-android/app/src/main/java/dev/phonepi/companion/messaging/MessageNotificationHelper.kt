package dev.phonepi.companion.messaging

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.phonepi.companion.R
import dev.phonepi.companion.assistant.AssistantProtocol
import dev.phonepi.companion.privacy.PhonePiQuiet
import dev.phonepi.companion.privacy.QuietHoldPolicy
import dev.phonepi.companion.receiver.MessageActionReceiver
import dev.phonepi.companion.ui.MessageReplyActivity
import dev.phonepi.companion.util.NotificationHelper
import org.json.JSONArray
import org.json.JSONObject

object MessageNotificationHelper {
    const val POSTED = "posted"
    const val HELD = "held"
    private const val HELD_PREFS = "phonepi_held_cards"
    private const val HELD_KEY = "cards"
    private const val HELD_OVERFLOW = "overflow"
    private const val HELD_CAP = QuietHoldPolicy.CAP
    private const val SUMMARY_ID = 4410
    private val lock = Any()

    fun post(
        context: Context,
        messageId: String,
        title: String,
        body: String,
        responseOptions: List<String>,
        priorityHigh: Boolean,
        includeReply: Boolean = false,
        includeDismiss: Boolean = true,
    ): String {
        if (!priorityHigh && PhonePiQuiet.isActive(context)) {
            hold(
                context,
                HeldCard(
                    messageId = messageId,
                    title = title,
                    body = body,
                    responseOptions = responseOptions,
                    priorityHigh = false,
                    includeReply = includeReply,
                    includeDismiss = includeDismiss,
                ),
            )
            return HELD
        }
        show(
            context,
            messageId,
            title,
            body,
            responseOptions,
            priorityHigh,
            includeReply,
            includeDismiss,
        )
        return POSTED
    }

    fun cancel(context: Context, messageId: String) {
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(messageId.hashCode())
    }

    fun releaseHeld(context: Context) {
        val app = context.applicationContext
        val (cards, overflow) = synchronized(lock) {
            val held = readHeld(app)
            val extra = overflowCount(app)
            writeHeld(app, emptyList())
            writeOverflow(app, 0)
            held to extra
        }
        if (cards.isEmpty() && overflow == 0) return
        for (card in cards) {
            show(
                app,
                card.messageId,
                card.title,
                card.body,
                card.responseOptions,
                card.priorityHigh,
                card.includeReply,
                card.includeDismiss,
            )
        }
        NotificationHelper.ensureChannels(app)
        val summary = NotificationCompat.Builder(app, NotificationHelper.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("PhonePi quiet ended")
            .setContentText(QuietHoldPolicy.summaryText(cards.size, overflow))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(cards.take(5).joinToString("\n") { it.title })
                        if (overflow > 0) {
                            if (isNotEmpty()) append('\n')
                            append("$overflow more held")
                        }
                        if (isEmpty()) append(QuietHoldPolicy.summaryText(cards.size, overflow))
                    },
                ),
            )
            .setAutoCancel(true)
            .build()
        app.getSystemService(android.app.NotificationManager::class.java)
            .notify(SUMMARY_ID, summary)
    }

    private fun hold(context: Context, card: HeldCard) {
        val app = context.applicationContext
        synchronized(lock) {
            val next = readHeld(app).filter { it.messageId != card.messageId } + card
            val dropped = QuietHoldPolicy.droppedCount(next.size, HELD_CAP)
            if (dropped > 0) writeOverflow(app, overflowCount(app) + dropped)
            writeHeld(app, next.takeLast(HELD_CAP))
        }
    }

    private fun show(
        context: Context,
        messageId: String,
        title: String,
        body: String,
        responseOptions: List<String>,
        priorityHigh: Boolean,
        includeReply: Boolean,
        includeDismiss: Boolean,
    ) {
        NotificationHelper.ensureChannels(context)
        val channel = if (priorityHigh) {
            NotificationHelper.CHANNEL_URGENT
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
            .extend(NotificationCompat.WearableExtender())

        val options = if (includeReply) {
            responseOptions.take(AssistantProtocol.MAX_ACTIONS)
        } else {
            responseOptions.ifEmpty { listOf("Yes", "No") }
        }
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

        if (includeReply) {
            builder.addAction(replyAction(context, messageId))
        }

        if (includeDismiss) {
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
        }

        context.getSystemService(android.app.NotificationManager::class.java)
            .notify(messageId.hashCode(), builder.build())
    }

    private fun replyAction(context: Context, messageId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(AssistantProtocol.KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()
        val replyIntent = Intent(context, MessageActionReceiver::class.java).apply {
            action = MessageActionReceiver.ACTION_REPLY
            putExtra(MessageActionReceiver.EXTRA_MESSAGE_ID, messageId)
        }
        val mutableFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPending = PendingIntent.getBroadcast(
            context,
            (messageId + ":reply").hashCode(),
            replyIntent,
            mutableFlags,
        )
        return NotificationCompat.Action.Builder(0, "Reply", replyPending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .extend(
                NotificationCompat.Action.WearableExtender()
                    .setHintDisplayActionInline(true),
            )
            .build()
    }

    private data class HeldCard(
        val messageId: String,
        val title: String,
        val body: String,
        val responseOptions: List<String>,
        val priorityHigh: Boolean,
        val includeReply: Boolean,
        val includeDismiss: Boolean,
    )

    private fun readHeld(context: Context): List<HeldCard> {
        val raw = context.getSharedPreferences(HELD_PREFS, Context.MODE_PRIVATE)
            .getString(HELD_KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("messageId")
                if (id.isBlank()) continue
                val options = obj.optJSONArray("responseOptions")
                add(
                    HeldCard(
                        messageId = id,
                        title = obj.optString("title"),
                        body = obj.optString("body"),
                        responseOptions = buildList {
                            if (options != null) {
                                for (i in 0 until options.length()) {
                                    val label = options.optString(i)
                                    if (label.isNotBlank()) add(label)
                                }
                            }
                        },
                        priorityHigh = obj.optBoolean("priorityHigh", false),
                        includeReply = obj.optBoolean("includeReply", false),
                        includeDismiss = obj.optBoolean("includeDismiss", true),
                    ),
                )
            }
        }
    }

    private fun overflowCount(context: Context): Int =
        context.getSharedPreferences(HELD_PREFS, Context.MODE_PRIVATE).getInt(HELD_OVERFLOW, 0)

    private fun writeOverflow(context: Context, count: Int) {
        context.getSharedPreferences(HELD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(HELD_OVERFLOW, count.coerceAtLeast(0))
            .apply()
    }

    private fun writeHeld(context: Context, cards: List<HeldCard>) {
        val array = JSONArray()
        for (card in cards) {
            val obj = JSONObject()
                .put("messageId", card.messageId)
                .put("title", card.title)
                .put("body", card.body)
                .put("priorityHigh", card.priorityHigh)
                .put("includeReply", card.includeReply)
                .put("includeDismiss", card.includeDismiss)
            val options = JSONArray()
            card.responseOptions.forEach { options.put(it) }
            obj.put("responseOptions", options)
            array.put(obj)
        }
        context.getSharedPreferences(HELD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HELD_KEY, array.toString())
            .apply()
    }
}
