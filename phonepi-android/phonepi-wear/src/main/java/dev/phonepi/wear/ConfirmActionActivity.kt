package dev.phonepi.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

class ConfirmActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        setContent {
            MaterialTheme {
                val context = LocalContext.current
                var status by remember { mutableStateOf("Sending…") }
                LaunchedEffect(kind) {
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
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(status)
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
