package dev.phonepi.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch

class ConfirmActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only EXTRA_KIND is read. Skip/auto/confirmed extras are ignored.
        val kind = ConfirmActionGuard.kindOf(intent.getStringExtra(EXTRA_KIND))
        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var status by remember {
                    mutableStateOf(
                        when (kind) {
                            KIND_QUIET -> "Quiet PhonePi for 1 hour?"
                            KIND_IM_OK -> "Send I'm OK?"
                            else -> "Unknown action"
                        },
                    )
                }
                var busy by remember { mutableStateOf(false) }
                // Confirm tap is mandatory. Never auto-send from extras.
                var done by remember { mutableStateOf(!ConfirmActionGuard.isKnownKind(kind)) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(status)
                    if (!done) {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = when (kind) {
                                        KIND_QUIET -> WearPhoneClient.send(
                                            context,
                                            WearPaths.QUIET,
                                            WearProtocol.encodeQuiet(60),
                                        )
                                        KIND_IM_OK -> WearPhoneClient.send(
                                            context,
                                            WearPaths.IM_OK,
                                            ByteArray(0),
                                        )
                                        else -> WearSendResult(false, "Unknown action")
                                    }
                                    status = when {
                                        result.ok && kind == KIND_QUIET -> "Quiet for 1 hour"
                                        result.ok && kind == KIND_IM_OK -> "I'm OK sent"
                                        else -> result.message
                                    }
                                    done = true
                                    busy = false
                                }
                            },
                        ) {
                            Text("Send")
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            onClick = { finish() },
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "dev.phonepi.wear.KIND"
        const val KIND_QUIET = "quiet"
        const val KIND_IM_OK = "im_ok"
    }
}
