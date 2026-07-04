package dev.phonepi.companion.connection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.phonepi.companion.R
import dev.phonepi.companion.protocol.ToolRouter
import dev.phonepi.companion.tools.AddContactTool
import dev.phonepi.companion.tools.AddSnippetTool
import dev.phonepi.companion.tools.BatteryTool
import dev.phonepi.companion.tools.ClipboardTool
import dev.phonepi.companion.tools.DeleteContactTool
import dev.phonepi.companion.tools.DeleteSnippetTool
import dev.phonepi.companion.tools.FindPhoneTool
import dev.phonepi.companion.tools.GetAllSnippetsTool
import dev.phonepi.companion.tools.GetContactByIdTool
import dev.phonepi.companion.tools.GetRecentCallsTool
import dev.phonepi.companion.tools.GetContactsTool
import dev.phonepi.companion.tools.GetLocationTool
import dev.phonepi.companion.tools.GetMessageResponseTool
import dev.phonepi.companion.tools.GetSnippetByIdTool
import dev.phonepi.companion.tools.GetSnippetsByTypeTool
import dev.phonepi.companion.tools.MakeCallTool
import dev.phonepi.companion.tools.SearchContactsTool
import dev.phonepi.companion.tools.SearchSnippetsTool
import dev.phonepi.companion.tools.SendMessageTool
import dev.phonepi.companion.tools.SendNotificationTool
// Legacy carrier SMS — use Google Messages gm_* tools on desktop instead
// import dev.phonepi.companion.tools.ListSmsSubscriptionsTool
// import dev.phonepi.companion.tools.SendSmsTool
// import dev.phonepi.companion.tools.GetSmsMessagesTool
import dev.phonepi.companion.tools.ShareSnippetTool
import dev.phonepi.companion.tools.StopAlarmTool
import dev.phonepi.companion.tools.TimerTool
import dev.phonepi.companion.tools.UpdateContactTool
import dev.phonepi.companion.tools.UpdateSnippetTool
import dev.phonepi.companion.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that holds the WebSocket. Typed connectedDevice so Android 15/16
 * does not apply the 6-hour dataSync cap. See design/03-connection-lifecycle.md.
 */
@Suppress("unused") // Started from ConnectionService.start() and AndroidManifest.xml
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: WebSocketManager

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(applicationContext)
        val ctx = applicationContext
        val router = ToolRouter(
            listOf(
                BatteryTool(ctx),
                ClipboardTool(ctx),
                TimerTool(ctx),
                SendNotificationTool(ctx),
                FindPhoneTool(ctx),
                StopAlarmTool(ctx),
                GetLocationTool(ctx),
                SendMessageTool(ctx),
                GetMessageResponseTool(ctx),
                AddSnippetTool(ctx),
                GetAllSnippetsTool(ctx),
                GetSnippetByIdTool(ctx),
                GetSnippetsByTypeTool(ctx),
                SearchSnippetsTool(ctx),
                UpdateSnippetTool(ctx),
                DeleteSnippetTool(ctx),
                ShareSnippetTool(ctx),
                // SendSmsTool(ctx),
                // ListSmsSubscriptionsTool(ctx),
                // GetSmsMessagesTool(ctx),
                MakeCallTool(ctx),
                GetRecentCallsTool(ctx),
                GetContactsTool(ctx),
                SearchContactsTool(ctx),
                GetContactByIdTool(ctx),
                AddContactTool(ctx),
                UpdateContactTool(ctx),
                DeleteContactTool(ctx),
            ),
        )
        manager = WebSocketManager(scope, router) { host, port ->
            ConnectionPrefs.saveSuccessfulConnection(applicationContext, host, port)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manager.disconnect()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val endpoint = ConnectionPrefs.resolveEndpoint(
            applicationContext,
            intent?.getStringExtra(EXTRA_HOST),
            intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 },
        )
        startForegroundNotification()
        manager.connect(endpoint.host, endpoint.port)
        return START_STICKY
    }

    /** Defensive: connectedDevice should not time out, but never crash if it does. */
    override fun onTimeout(startId: Int) {
        manager.disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Phone link active")
            .setSmallIcon(R.drawable.ic_stat_link)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mcp_connection"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.phonepi.companion.action.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        fun start(context: Context, host: String, port: Int) {
            val intent = Intent(context, ConnectionService::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
