package dev.phonepi.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonepi.companion.findphone.FindPhoneService

/** Full-screen stop button for an active find-phone alarm. */
class FindPhoneDismissActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("PhonePi find phone is ringing")
                        Button(
                            modifier = Modifier.padding(top = 24.dp),
                            onClick = {
                                FindPhoneService.stop(this@FindPhoneDismissActivity, sessionId)
                                finish()
                            },
                        ) {
                            Text("Stop alarm")
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, FindPhoneDismissActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
    }
}
