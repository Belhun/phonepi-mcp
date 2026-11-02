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
    fun upsertNewServerBorrowsSharedTokenFromConnectForm() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "k"))
        val (out, entry) = ServerList.upsert(
            list,
            null,
            "dell-mini-pc.tailcbcc46.ts.net",
            443,
            "",
            false,
            borrowSharedToken = true,
        )
        assertEquals(3, out.size)
        assertEquals("dell-mini-pc", entry.name)
        assertEquals("k", entry.token)
        assertFalse(entry.primary)
    }

    @Test
    fun deepLinkWithoutTokenDoesNotInheritSavedToken() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "keep-secret"))
        val (out, entry) = ServerList.upsert(
            list,
            null,
            "dell-mini-pc.tailcbcc46.ts.net",
            443,
            "",
            makePrimary = true,
            borrowSharedToken = false,
        )
        assertEquals("", entry.token)
        assertTrue(entry.primary)
        assertEquals("keep-secret", out.first { it.id == "hp" }.token)
        assertEquals("keep-secret", out.first { it.id == "box" }.token)
    }

    @Test
    fun deepLinkNonAllowlistedHostIsRejected() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "keep-secret"))
        try {
            ServerList.upsert(list, null, "evil.example", 443, "", makePrimary = true)
            throw AssertionError("expected DisallowedPhonePiHostException")
        } catch (e: DisallowedPhonePiHostException) {
            assertEquals("evil.example", e.host)
        }
    }

    @Test
    fun deepLinkOtherTailnetIsRejected() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "keep-secret"))
        try {
            ServerList.upsert(
                list,
                null,
                "x.attacker.ts.net",
                443,
                "attacker-token",
                makePrimary = true,
            )
            throw AssertionError("expected DisallowedPhonePiHostException")
        } catch (e: DisallowedPhonePiHostException) {
            assertEquals("x.attacker.ts.net", e.host)
        }
    }

    @Test
    fun deepLinkTokenOnNewLoopbackHostIsRefused() {
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "keep-secret"))
        try {
            ServerList.upsert(
                list,
                null,
                "127.0.0.1",
                7000,
                "attacker-token",
                makePrimary = false,
                borrowSharedToken = false,
            )
            throw AssertionError("expected LinkTokenNotOnTailnetException")
        } catch (e: LinkTokenNotOnTailnetException) {
            assertEquals("127.0.0.1", e.host)
        }
    }

    @Test
    fun deepLinkTokenOnThisTailnetIsKept() {
        val (_, entry) = ServerList.upsert(
            emptyList(),
            "HP",
            "hp1.tailcbcc46.ts.net",
            443,
            "from-qr",
            makePrimary = true,
            borrowSharedToken = false,
        )
        assertEquals("from-qr", entry.token)
        assertEquals("hp1.tailcbcc46.ts.net", entry.host)
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

    @Test
    fun probeRejectsNonAllowlistedHostWithoutConnecting() {
        val server = ServerEntry("evil", "Evil", "evil.example", 443, "secret-token", primary = true)
        assertFalse(ServerProbe.probe(server))
    }

    @Test
    fun percentEncodedHashHostSetupLinkIsRejected() {
        val req = SetupLink.parseRequest(
            "phonepi://setup?host=evil.com%23.tailcbcc46.ts.net&token=x",
        )
        assertNotNull(req)
        assertEquals("evil.com#.tailcbcc46.ts.net", req!!.target.host)
        val list = ServerList.seed(ConnectionPrefs.Endpoint("grokbot-box.tailcbcc46.ts.net", 443, "keep-secret"))
        try {
            ServerList.upsert(
                list,
                req.name,
                req.target.host,
                req.target.port,
                req.target.wsToken,
                req.primary,
            )
            throw AssertionError("expected DisallowedPhonePiHostException")
        } catch (e: DisallowedPhonePiHostException) {
            assertEquals("evil.com#.tailcbcc46.ts.net", e.host)
        }
    }

    @Test
    fun percentEncodedQuestionUrlSetupLinkIsRejected() {
        val req = SetupLink.parseRequest(
            "phonepi://setup?url=https%3A%2F%2Fevil.com%3F.tailcbcc46.ts.net",
        )
        assertNotNull(req)
        assertEquals("evil.com?.tailcbcc46.ts.net", req!!.target.host)
        val list = ServerList.seed(ConnectionPrefs.Endpoint("hp1.tailcbcc46.ts.net", 443, "keep-secret"))
        try {
            ServerList.upsert(
                list,
                req.name,
                req.target.host,
                req.target.port,
                req.target.wsToken,
                req.primary,
            )
            throw AssertionError("expected DisallowedPhonePiHostException")
        } catch (e: DisallowedPhonePiHostException) {
            assertTrue(e.host.contains("evil.com"))
        }
    }
}
