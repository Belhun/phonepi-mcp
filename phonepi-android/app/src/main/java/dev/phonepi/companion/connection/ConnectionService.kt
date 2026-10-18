package dev.phonepi.companion.connection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import dev.phonepi.companion.tools.AddLocationTool
import dev.phonepi.companion.tools.GetLocationTool
import dev.phonepi.companion.tools.GetNotificationsTool
import dev.phonepi.companion.photos.GetPhotoTool
import dev.phonepi.companion.photos.GetRecentPhotosTool
import dev.phonepi.companion.photos.MediaStorePhotoLibrary
import dev.phonepi.companion.photos.PhotoAccess
import dev.phonepi.companion.photos.PhotoGate
import dev.phonepi.companion.tools.GetVolumesTool
import dev.phonepi.companion.tools.SetVolumeTool
import dev.phonepi.companion.tools.ListLocationsTool
import dev.phonepi.companion.tools.ListNotificationAppsTool
import dev.phonepi.companion.tools.NavigateTool
import dev.phonepi.companion.tools.PushUpdateTool
import dev.phonepi.companion.tools.RemoveLocationTool
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
import dev.phonepi.companion.tools.DismissAlarmTool
import dev.phonepi.companion.tools.DismissTimerTool
import dev.phonepi.companion.tools.ListAlarmsTool
import dev.phonepi.companion.tools.SetAlarmTool
import dev.phonepi.companion.tools.TimerTool
import dev.phonepi.companion.tools.UpdateContactTool
import dev.phonepi.companion.tools.UpdateSnippetTool
import dev.phonepi.companion.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that holds the WebSocket. Typed connectedDevice so Android 15/16
 * does not apply the 6-hour dataSync cap. See design/03-connection-lifecycle.md.
 */
@Suppress("unused") // Started from ConnectionService.start() and AndroidManifest.xml
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: WebSocketManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(applicationContext)
        val ctx = applicationContext
        val photos = MediaStorePhotoLibrary(ctx)
        val router = ToolRouter(
            listOf(
                BatteryTool(ctx),
                ClipboardTool(ctx),
                TimerTool(ctx),
                SetAlarmTool(ctx),
                DismissAlarmTool(ctx),
                DismissTimerTool(ctx),
                ListAlarmsTool(ctx),
                SendNotificationTool(ctx),
                FindPhoneTool(ctx),
                StopAlarmTool(ctx),
                GetLocationTool(ctx),
                ListLocationsTool(ctx),
                AddLocationTool(ctx),
                RemoveLocationTool(ctx),
                NavigateTool(ctx),
                PushUpdateTool(ctx),
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
                GetNotificationsTool(ctx),
                ListNotificationAppsTool(ctx),
                GetVolumesTool(ctx),
                SetVolumeTool(ctx),
                GetRecentPhotosTool(PhotoGate { PhotoAccess.denial(ctx) }, photos),
                GetPhotoTool(PhotoGate { PhotoAccess.denial(ctx) }, photos),
                GetContactsTool(ctx),
                SearchContactsTool(ctx),
                GetContactByIdTool(ctx),
                AddContactTool(ctx),
                UpdateContactTool(ctx),
                DeleteContactTool(ctx),
            ),
        )
        manager = WebSocketManager(
            scope,
            router,
            onConnected = { host, port, token ->
                ConnectionPrefs.saveSuccessfulConnection(applicationContext, host, port, token)
            },
            permissionPayload = {
                PhonePermissionReport.toJson(PhonePermissionSnapshot.capture(applicationContext))
            },
        )
        createChannel()
        registerNetworkCallback()
        scope.launch {
            ConnectionController.state.collect { state ->
                updateForeground(state.statusLabel())
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (::manager.isInitialized) manager.publishPermissions()
            }
        }
        serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manager.disconnect()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_NUDGE) {
            startForegroundNotification(ConnectionController.state.value.statusLabel())
            manager.nudge("app foreground")
            manager.publishPermissions(force = true)
            return START_STICKY
        }
        val endpoint = ConnectionPrefs.resolveEndpoint(
            applicationContext,
            intent?.getStringExtra(EXTRA_HOST),
            intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 },
            intent?.getStringExtra(EXTRA_WS_TOKEN),
        )
        ConnectionPrefs.saveEndpoint(applicationContext, endpoint.host, endpoint.port, endpoint.wsToken)
        startForegroundNotification()
        manager.connect(endpoint.host, endpoint.port, endpoint.wsToken)
        return START_STICKY
    }

    /** Defensive: connectedDevice should not time out, but never crash if it does. */
    override fun onTimeout(startId: Int) {
        manager.disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        serviceRunning = false
        unregisterNetworkCallback()
        if (::manager.isInitialized) manager.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun startForegroundNotification(text: String = "Phone link active") {
        updateForeground(text)
    }

    private fun updateForeground(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
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

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                manager.onNetworkChanged("network available")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                manager.onNetworkChanged("network capabilities changed")
            }
        }
        connectivity.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
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
        private const val ACTION_NUDGE = "dev.phonepi.companion.action.NUDGE"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_WS_TOKEN = "ws_token"

        @Volatile
        private var serviceRunning = false

        /** Retry immediately when the process is already holding the link. */
        fun nudgeIfRunning(context: Context) {
            if (!serviceRunning) return
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_NUDGE
            }
            context.startService(intent)
        }

        fun start(context: Context, host: String, port: Int, wsToken: String = "") {
            val intent = Intent(context, ConnectionService::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_WS_TOKEN, wsToken)
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
