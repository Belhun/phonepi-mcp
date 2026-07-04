package dev.phonepi.companion.tools

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

enum class TimerVia {
    CLOCK_APP,
    ALARM_MANAGER,
}

data class TimerHandoffResult(
    val via: TimerVia,
    val detail: String? = null,
)

/** Waits for [TimerBridgeActivity] to report whether Clock accepted the intent. */
object TimerHandoffCoordinator {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TimerHandoffResult>>()

    fun register(token: String): CompletableDeferred<TimerHandoffResult> {
        val deferred = CompletableDeferred<TimerHandoffResult>()
        pending[token] = deferred
        return deferred
    }

    fun complete(token: String, result: TimerHandoffResult) {
        pending.remove(token)?.complete(result)
    }

    fun cancel(token: String) {
        pending.remove(token)?.cancel()
    }
}
