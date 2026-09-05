package dev.phonepi.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectEndpointTest {
    @Test
    fun tailscaleServeHostUsesWss443() {
        val target = ConnectEndpoint.parse("dell-mini-pc.tailcbcc46.ts.net", "443")
        assertEquals("dell-mini-pc.tailcbcc46.ts.net", target.host)
        assertEquals(443, target.port)
        assertEquals("wss://dell-mini-pc.tailcbcc46.ts.net:443/phonepi", target.url)
    }

    @Test
    fun tailscaleIpRewritesToHostnameForSni() {
        val target = ConnectEndpoint.parse("100.79.4.36", "443")
        assertEquals("dell-mini-pc.tailcbcc46.ts.net", target.host)
        assertEquals(443, target.port)
        assertTrue(target.url.startsWith("wss://dell-mini-pc.tailcbcc46.ts.net:443/"))
    }

    @Test
    fun leftoverDirectPortOnTsNetBecomes443() {
        val target = ConnectEndpoint.parse("dell-mini-pc.tailcbcc46.ts.net", "11041")
        assertEquals(443, target.port)
        assertTrue(target.url.startsWith("wss://"))
    }

    @Test
    fun loopbackStaysWs() {
        val target = ConnectEndpoint.parse("127.0.0.1", "7000")
        assertEquals("127.0.0.1", target.host)
        assertEquals(7000, target.port)
        assertEquals("ws://127.0.0.1:7000/phonepi", target.url)
    }

    @Test
    fun setupDeeplinkFillsHostAndPort() {
        val target = SetupLink.parse(
            "phonepi://setup?host=dell-mini-pc.tailcbcc46.ts.net&port=443",
        )
        assertNotNull(target)
        assertEquals("dell-mini-pc.tailcbcc46.ts.net", target!!.host)
        assertEquals(443, target.port)
    }

    @Test
    fun setupDeeplinkSurvivesGluedPortInHost() {
        val target = SetupLink.parse(
            "phonepi://setup?host=dell-mini-pc.tailcbcc46.ts.net%26port=443",
        )
        assertNotNull(target)
        assertEquals("dell-mini-pc.tailcbcc46.ts.net", target!!.host)
        assertEquals(443, target.port)
    }

    @Test
    fun wssUrlInQrStillParses() {
        val target = SetupLink.parse("wss://dell-mini-pc.tailcbcc46.ts.net:443/phonepi")
        assertNotNull(target)
        assertEquals("dell-mini-pc.tailcbcc46.ts.net", target!!.host)
        assertEquals(443, target.port)
    }
}
