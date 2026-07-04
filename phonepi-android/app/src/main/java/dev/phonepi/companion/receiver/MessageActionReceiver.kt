package dev.phonepi.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.companion.messaging.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_RESPOND -> {
                        val response = intent.getStringExtra(EXTRA_RESPONSE) ?: return@launch
                        val index = intent.getIntExtra(EXTRA_RESPONSE_INDEX, 0)
                        MessageRepository.recordResponse(context, messageId, response, index)
                    }
                    ACTION_DISMISS -> MessageRepository.recordDismiss(context, messageId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESPOND = "dev.phonepi.companion.action.MESSAGE_RESPOND"
        const val ACTION_DISMISS = "dev.phonepi.companion.action.MESSAGE_DISMISS"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_RESPONSE = "response"
        const val EXTRA_RESPONSE_INDEX = "responseIndex"
    }
}
