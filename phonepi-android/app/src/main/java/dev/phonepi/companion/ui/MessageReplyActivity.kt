package dev.phonepi.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonepi.companion.data.MessageEntity
import dev.phonepi.companion.messaging.MessageRepository
import kotlinx.coroutines.launch

/** Opens when the user taps an interactive message notification. */
class MessageReplyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: run {
            finish()
            return
        }

        setContent {
            val scope = rememberCoroutineScope()
            var message by remember { mutableStateOf<MessageEntity?>(null) }
            var options by remember { mutableStateOf<List<String>>(emptyList()) }
            var answered by remember { mutableStateOf(false) }

            LaunchedEffect(messageId) {
                val row = MessageRepository.get(this@MessageReplyActivity, messageId)
                message = row
                options = MessageRepository.decodeOptions(row?.responseOptions)
                    .ifEmpty { listOf("Yes", "No") }
                if (row?.state == "responded" || row?.state == "dismissed") {
                    answered = true
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val row = message
                        if (row == null) {
                            Text("Loading…")
                        } else {
                            Text(row.title, style = MaterialTheme.typography.headlineSmall)
                            Text(row.body, style = MaterialTheme.typography.bodyLarge)
                            when (row.state) {
                                "responded" -> Text("You answered: ${row.response}")
                                "dismissed" -> Text("Dismissed")
                                "expired" -> Text("Expired")
                                "pending" -> if (!answered) {
                                    options.forEachIndexed { index, label ->
                                        Button(
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = {
                                                scope.launch {
                                                    MessageRepository.recordResponse(
                                                        this@MessageReplyActivity,
                                                        messageId,
                                                        label,
                                                        index,
                                                    )
                                                    answered = true
                                                    message = MessageRepository.get(
                                                        this@MessageReplyActivity,
                                                        messageId,
                                                    )
                                                }
                                            },
                                        ) {
                                            Text(label)
                                        }
                                    }
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            scope.launch {
                                                MessageRepository.recordDismiss(
                                                    this@MessageReplyActivity,
                                                    messageId,
                                                )
                                                answered = true
                                                finish()
                                            }
                                        },
                                    ) {
                                        Text("Dismiss")
                                    }
                                } else {
                                    Text("Response saved")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "messageId"

        fun intent(context: Context, messageId: String): Intent =
            Intent(context, MessageReplyActivity::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, messageId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
