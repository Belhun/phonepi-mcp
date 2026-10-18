package dev.phonepi.companion.history

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes unsynced rows over the phone WebSocket. The box stores the copy.
 * A clear is sent before any new rows so a wipe cannot be undone by an old batch.
 */
object LocationHistorySync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile
    private var sender: ((String) -> Boolean)? = null
    @Volatile
    private var appContext: Context? = null

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
        LocationHistoryPrefs.setPendingRemoteClear(context, true)
        nudge()
    }

    fun onFrame(text: String) {
        val context = appContext ?: return
        if (parseClearedAck(text)) {
            LocationHistoryPrefs.setPendingRemoteClear(context, false)
            nudge()
            return
        }
        val ack = parseSyncAck(text) ?: return
        scope.launch {
            val dao = LocationHistoryDatabase.get(context).dao()
            if (ack.accepted.isNotEmpty()) dao.markSynced(ack.accepted)
            if (ack.rejected.isNotEmpty()) dao.markRejected(ack.rejected)
        }
    }

    private suspend fun flush(context: Context) {
        mutex.withLock {
            val send = sender ?: return
            if (LocationHistoryPrefs.pendingRemoteClear(context)) {
                if (!send(locationClearedMessage())) return
                return
            }
            val dao = LocationHistoryDatabase.get(context).dao()
            val batch = dao.pending(40).map { it.toRecord() }
            if (batch.isEmpty()) return
            send(locationSyncMessage(batch))
        }
    }
}
