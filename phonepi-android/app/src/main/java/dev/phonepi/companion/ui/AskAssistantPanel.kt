package dev.phonepi.companion.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phonepi.companion.assistant.AskAssistantActivity
import dev.phonepi.companion.assistant.AssistantEvents

@Composable
fun AskAssistantPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ask assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            "Start a prompt from the phone without changing any settings. " +
                "Add the Ask assistant Quick Settings tile, or long-press the PhonePi icon " +
                "and drag Ask assistant to the home screen. Pixel Quick Tap: Settings → System → Gestures → Quick Tap → Open app → Ask assistant.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                context.startActivity(
                    AskAssistantActivity.intent(context, "app").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                )
            },
        ) {
            Text("Ask assistant now")
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { AssistantEvents.clearAll(context.applicationContext) },
        ) {
            Text("Delete prompts and replies")
        }
        Text(
            "Deletes queued prompts/replies on the phone and the box copy. " +
                "A spoken or typed reply is data, not an approval.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
