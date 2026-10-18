package dev.phonepi.companion.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import dev.phonepi.companion.connection.ConnectionService
import dev.phonepi.companion.connection.SetupBus
import dev.phonepi.companion.connection.SetupLink

@Suppress("unused") // Launcher activity in AndroidManifest.xml
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            MaterialTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    ConnectScreen(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ConnectionService.nudgeIfRunning(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = sharedUri(intent)
            if (uri != null) TimelineImportBus.offer(uri)
        }
        val data = intent?.data?.toString() ?: return
        val target = SetupLink.parse(data) ?: return
        SetupBus.offer(target)
    }

    private fun sharedUri(intent: Intent): Uri? {
        val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return extra ?: intent.clipData?.getItemAt(0)?.uri
    }
}
