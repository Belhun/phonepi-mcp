package dev.phonepi.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerListTest {
    @Test
    fun migrationFrom032KeepsBoxHostPortAndToken() {
        val seeded = ServerList.seed(
            ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "secret-x"),
        )
        assertEquals(2, seeded.size)
        val hp = seeded[0]
        val box = seeded[1]
        assertEquals("HP", hp.name)
        assertEquals("hp1.tailcbcc46.ts.net", hp.host)
        assertEquals(443, hp.port)
        assertTrue(hp.primary)
        assertEquals("secret-x", hp.token)
        assertEquals("Box", box.name)
        assertEquals("grokbot-box.tailcbcc46.ts.net", box.host)
        assertFalse(box.primary)
        assertEquals("secret-x", box.token)
    }

    @Test
    fun migrationWithNothingSavedUsesBoxServeName() {
        val seeded = ServerList.seed(null)
        assertEquals(ServerList.BOX_HOST, seeded[1].host)
        assertEquals(443, seeded[1].port)
        assertEquals("", seeded[0].token)
    }

    @Test
    fun migrationWhenAlreadyOnHpDoesNotDuplicate() {
        val seeded = ServerList.seed(ConnectionPrefs.Endpoint("100.108.138.113", 443, "t"))
        assertEquals(ServerList.BOX_HOST, seeded[1].host)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "t"))
        assertEquals(list, ServerList.decode(ServerList.encode(list)))
        assertNull(ServerList.decode("not json"))
    }

    @Test
    fun hpIpMapsToMagicDnsName() {
        assertEquals("100.108.138.113", TailscaleNet.hostToIp["hp1.tailcbcc46.ts.net"])
        assertEquals("hp1.tailcbcc46.ts.net", TailscaleNet.canonicalHost("100.108.138.113"))
        val target = ConnectEndpoint.parse("100.108.138.113", "443")
        assertEquals("wss://hp1.tailcbcc46.ts.net:443/phonepi", target.url)
    }

    @Test
    fun upsertBlankTokenKeepsExisting() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "keep"))
        val (out, entry) = ServerList.upsert(list, "Main", "hp1.tailcbcc46.ts.net", 443, "", makePrimary = true)
        assertEquals("keep", entry.token)
        assertEquals("Main", entry.name)
        assertEquals(2, out.size)
        assertEquals(1, out.count { it.primary })
    }

    @Test
    fun upsertPrimaryDemotesOthers() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "k"))
        val (out, entry) = ServerList.upsert(list, null, "grokbot-box.tailcbcc46.ts.net", 443, null, makePrimary = true)
        assertEquals("box", entry.id)
        assertTrue(out.first { it.id == "box" }.primary)
        assertFalse(out.first { it.id == "hp" }.primary)
        assertEquals("box", ServerList.ordered(out).first().id)
    }

    @Test
    fun upsertNewServerBorrowsSharedToken() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "k"))
        val (out, entry) = ServerList.upsert(list, null, "dell-mini-pc.tailcbcc46.ts.net", 443, "", false)
        assertEquals(3, out.size)
        assertEquals("dell-mini-pc", entry.name)
        assertEquals("k", entry.token)
        assertFalse(entry.primary)
    }

    @Test
    fun setupLinkReadsNameAndPrimary() {
        val req = SetupLink.parseRequest(
            "phonepi://setup?host=hp1.tailcbcc46.ts.net&port=443&name=HP&primary=true",
        )
        assertNotNull(req)
        assertEquals("HP", req!!.name)
        assertTrue(req.primary)
        assertEquals("hp1.tailcbcc46.ts.net", req.target.host)
        assertEquals("", req.target.wsToken)
    }

    @Test
    fun setupLinkWithoutPrimaryIsNotPrimary() {
        val req = SetupLink.parseRequest("phonepi://setup?host=grokbot-box.tailcbcc46.ts.net&token=abc")
        assertNotNull(req)
        assertFalse(req!!.primary)
        assertNull(req.name)
        assertEquals("abc", req.target.wsToken)
    }

    @Test
    fun probeStatusRules() {
        assertTrue(ServerProbe.isHealthyStatus(200))
        assertTrue(ServerProbe.isHealthyStatus(404))
        assertFalse(ServerProbe.isHealthyStatus(401))
        assertFalse(ServerProbe.isHealthyStatus(502))
    }
}
