package dev.phonepi.wear

import android.app.AlarmManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

class NextAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val line = remember {
                    val localTrigger = context.getSystemService(AlarmManager::class.java)
                        ?.nextAlarmClock
                        ?.triggerTime
                    NextAlarmFormat.longText(
                        localTrigger ?: PresenceCache.nextAlarmEpochMs(context),
                        PresenceCache.label(context),
                        System.currentTimeMillis(),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Next alarm", style = MaterialTheme.typography.titleSmall)
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
