package dev.phonepi.companion.wear

import android.app.AlarmManager
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.phonepi.companion.presence.PresencePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Phone-side Data Layer listener. Receives watch messages and never reads or
 * writes the PhonePi token.
 */
class WearListenerService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        WearBridge.ensure(applicationContext)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        if (!path.startsWith(WearPaths.PREFIX)) return
        val payload = String(messageEvent.data, Charsets.UTF_8)
        val nodeId = messageEvent.sourceNodeId
        val app = applicationContext
        Log.i(TAG, "path=$path bytes=${messageEvent.data.size}")
        WearBridge.scope.launch {
            val ack = WearBridge.dispatcher(app).dispatch(path, payload)
            runCatching {
                Wearable.getMessageClient(app)
                    .sendMessage(nodeId, WearPaths.ACK, ack.encode())
                    .await()
            }
            WearStatePublisher.publish(app)
        }
    }

    companion object {
        private const val TAG = "PhonePiWear"
    }
}

object WearBridge {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var presence: PresenceLabelSource = PresenceLabelSource.Stub

    @Volatile
    var actionsOverride: WearInboundActions? = null

    fun ensure(context: android.content.Context) {
        val app = context.applicationContext
        runCatching {
            Wearable.getCapabilityClient(app).addLocalCapability(WearPaths.PHONE_CAPABILITY)
        }
        if (presence === PresenceLabelSource.Stub) {
            presence = PresenceLabelSource {
                PresenceWatchLabel.label(
                    enabled = PresencePrefs.enabled(app),
                    paused = PresencePrefs.paused(app),
                    placeName = PresencePrefs.memory(app).placeName,
                )
            }
        }
        WearStatePublisher.publish(app)
    }

    fun dispatcher(context: android.content.Context): WearMessageDispatcher {
        val app = context.applicationContext
        val actions = actionsOverride ?: WearInboundActionsAdapter.create(app)
        return WearMessageDispatcher(
            actions = actions,
            nextAlarmEpochMs = {
                app.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
            },
            presence = presence,
        )
    }
}
