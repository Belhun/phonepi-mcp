package dev.phonepi.companion.presence

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes the latest presence snapshot over the phone WebSocket.
 * A clear is sent before a new snapshot so a wipe cannot be undone.
 */
object PresenceSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile
    private var sender: ((String) -> Boolean)? = null
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var pending: PresenceSnapshot? = null

    fun attach(context: Context, send: (String) -> Boolean) {
        appContext = context.applicationContext
        sender = send
        nudge()
    }

    fun detach() {
        sender = null
    }

    fun nudge() {
        val context = appContext ?: return
        scope.launch { flush(context) }
    }

    fun requestClear(context: Context) {
        appContext = context.applicationContext
        PresencePrefs.setPendingRemoteClear(context, true)
        pending = null
        nudge()
    }

    fun push(context: Context, snapshot: PresenceSnapshot) {
        appContext = context.applicationContext
        pending = snapshot
        nudge()
    }

    fun onFrame(text: String) {
        val context = appContext ?: return
        if (parsePresenceClearedAck(text)) {
            PresencePrefs.setPendingRemoteClear(context, false)
            nudge()
            return
        }
        if (parsePresenceSyncAck(text)) {
            pending = null
        }
    }

    private suspend fun flush(context: Context) {
        mutex.withLock {
            val send = sender ?: return
            if (PresencePrefs.pendingRemoteClear(context)) {
                if (!send(presenceClearedMessage())) return
                return
            }
            val snapshot = pending ?: return
            send(presenceSyncMessage(snapshot))
        }
    }
}
