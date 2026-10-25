package dev.phonepi.companion.usage

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes daily usage rollups over the phone WebSocket. The box stores the copy.
 * A clear is sent before any new days so a wipe cannot be undone by an old batch.
 */
object UsageSync {
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
        UsagePrefs.setPendingRemoteClear(context, true)
        nudge()
    }

    fun onFrame(text: String) {
        val context = appContext ?: return
        if (parseUsageClearedAck(text)) {
            UsagePrefs.setPendingRemoteClear(context, false)
            nudge()
            return
        }
        if (parseUsageAck(text) != null) {
            nudge()
        }
    }

    private suspend fun flush(context: Context) {
        mutex.withLock {
            val send = sender ?: return
            if (UsagePrefs.pendingRemoteClear(context)) {
                send(usageClearedMessage())
                return
            }
            val days = UsageCollector.store(context).readAll()
            val meta = UsageEngine.currentMeta(context)
            send(usageSyncMessage(meta, days))
        }
    }
}
