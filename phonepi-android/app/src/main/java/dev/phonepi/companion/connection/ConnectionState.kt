package dev.phonepi.companion.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState

    /** Still trying. [retryInMs] is how long until the next open. There is no terminal failure. */
    data class Reconnecting(
        val attempt: Int,
        val retryInMs: Long,
        val reason: String,
    ) : ConnectionState
}

fun ConnectionState.statusLabel(): String = when (this) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> {
        val seconds = ((retryInMs + 999L) / 1000L).coerceAtLeast(0L)
        val why = reason.trim().ifEmpty { "offline" }
        "Reconnecting in ${seconds}s ($why)"
    }
}

/** App-wide connection state. The service writes it; the UI observes it. */
object ConnectionController {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun update(state: ConnectionState) {
        _state.value = state
    }
}
