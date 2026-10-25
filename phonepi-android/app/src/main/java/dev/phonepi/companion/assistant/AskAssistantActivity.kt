package dev.phonepi.companion.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Quick voice/text prompt. Opened from the Quick Settings tile, the
 * home-screen shortcut, the Ask assistant launcher alias (Pixel Quick Tap),
 * or a future watch relay intent. Does not change phone settings.
 */
class AskAssistantActivity : ComponentActivity() {
    private val draft = mutableStateOf("")
    private var source: String = "app"

    private val voice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (!spoken.isNullOrEmpty()) draft.value = spoken
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = resolveSource(intent)
        setContent {
            var text by draft
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Ask assistant", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "This goes to PhonePi on the box. Bots read it with get_inbound_prompts.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.take(AssistantProtocol.PROMPT_MAX) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("What should the assistant do?") },
                            minLines = 3,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { startVoice() },
                        ) {
                            Text("Speak")
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = text.trim().isNotEmpty(),
                            onClick = {
                                val id = AssistantEvents.emitPrompt(
                                    this@AskAssistantActivity,
                                    text,
                                    source,
                                )
                                if (id != null) finish()
                            },
                        ) {
                            Text("Send")
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { finish() },
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    private fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask assistant")
        }
        runCatching { voice.launch(intent) }
    }

    companion object {
        const val ACTION_ASK = "dev.phonepi.companion.action.ASK_ASSISTANT"
        const val EXTRA_SOURCE = "source"

        fun intent(context: Context, source: String): Intent =
            Intent(context, AskAssistantActivity::class.java).apply {
                action = ACTION_ASK
                putExtra(EXTRA_SOURCE, source)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        fun resolveSource(intent: Intent?): String {
            val extra = intent?.getStringExtra(EXTRA_SOURCE)
            if (extra != null) return AssistantProtocol.normalizeSource(extra)
            if (intent?.action == ACTION_ASK) return "watch_relay"
            val data = intent?.data
            if (data != null && data.scheme == "phonepi" && data.host == "ask") {
                return AssistantProtocol.normalizeSource(data.getQueryParameter("source") ?: "shortcut")
            }
            return "quick_tap"
        }
    }
}
