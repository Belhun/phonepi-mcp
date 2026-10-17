package dev.phonepi.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStateLabelTest {
    @Test
    fun labelsStayHonest() {
        assertEquals("Connected", ConnectionState.Connected.statusLabel())
        assertEquals("Connecting…", ConnectionState.Connecting.statusLabel())
        assertEquals("Disconnected", ConnectionState.Disconnected.statusLabel())
        assertEquals(
            "Reconnecting in 5s (connection closed)",
            ConnectionState.Reconnecting(3, 4_200L, "connection closed").statusLabel(),
        )
        assertEquals(
            "Reconnecting in 300s (no inbound for 45s)",
            ConnectionState.Reconnecting(12, 300_000L, "no inbound for 45s").statusLabel(),
        )
    }
}
