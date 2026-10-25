package dev.phonepi.companion.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonepi.companion.health.HEALTH_PRIVACY_BODY
import dev.phonepi.companion.health.HEALTH_PRIVACY_CLOSE
import dev.phonepi.companion.health.HEALTH_PRIVACY_CONTINUE
import dev.phonepi.companion.health.HEALTH_PRIVACY_TITLE
import dev.phonepi.companion.health.HealthConnectPrefs

/**
 * Android requires this screen before Health Connect permission prompts
 * (ACTION_SHOW_PERMISSIONS_RATIONALE and VIEW_PERMISSION_USAGE).
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val continueToGrant = intent.getBooleanExtra(EXTRA_CONTINUE_TO_GRANT, false)
        setContent {
            MaterialTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { inner ->
                    HealthPrivacyScreen(
                        continueToGrant = continueToGrant,
                        onContinue = {
                            HealthConnectPrefs.setRationaleShown(applicationContext, true)
                            setResult(Activity.RESULT_OK)
                            finish()
                        },
                        onClose = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        modifier = Modifier.padding(inner),
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_CONTINUE_TO_GRANT = "continue_to_grant"
    }
}

@Composable
fun HealthPrivacyScreen(
    continueToGrant: Boolean,
    onContinue: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(HEALTH_PRIVACY_TITLE, style = MaterialTheme.typography.headlineSmall)
        Text(HEALTH_PRIVACY_BODY, style = MaterialTheme.typography.bodyMedium)
        if (continueToGrant) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(HEALTH_PRIVACY_CONTINUE)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(HEALTH_PRIVACY_CLOSE)
            }
        } else {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(HEALTH_PRIVACY_CLOSE)
            }
        }
    }
}
