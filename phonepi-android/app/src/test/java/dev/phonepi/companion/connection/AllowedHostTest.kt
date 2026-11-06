package dev.phonepi.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedHostTest {
    @Test
    fun allowlistAcceptsThisTailnetTailscaleAndLoopback() {
        assertTrue(AllowedHost.isAllowlisted("hp1.tailcbcc46.ts.net"))
        assertTrue(AllowedHost.isAllowlisted("grokbot-box.tailcbcc46.ts.net"))
        assertTrue(AllowedHost.isAllowlisted("dell-mini-pc.tailcbcc46.ts.net"))
        assertTrue(AllowedHost.isAllowlisted("100.64.0.1"))
        assertTrue(AllowedHost.isAllowlisted("100.127.255.255"))
        assertTrue(AllowedHost.isAllowlisted("fd7a:115c:a1e0::1"))
        assertTrue(AllowedHost.isAllowlisted("[fd7a:115c:a1e0:0:0:0:0:1]"))
        assertTrue(AllowedHost.isAllowlisted("127.0.0.1"))
        assertTrue(AllowedHost.isAllowlisted("localhost"))
        assertTrue(AllowedHost.isAllowlisted("::1"))
    }

    @Test
    fun otherTailnetsAndPublicHostsAreRejected() {
        assertFalse(AllowedHost.isAllowlisted("x.attacker.ts.net"))
        assertFalse(AllowedHost.isAllowlisted("hp1-tailcbcc46.attacker.ts.net"))
        assertFalse(AllowedHost.isAllowlisted("foo.ts.net"))
        assertFalse(AllowedHost.isAllowlisted("evil.example"))
        assertFalse(AllowedHost.isAllowlisted("evil.example.com"))
        assertFalse(AllowedHost.isAllowlisted("notts.net"))
        assertFalse(AllowedHost.isAllowlisted("foo.ts.net.attacker.com"))
        assertFalse(AllowedHost.isAllowlisted("10.0.2.2"))
        assertFalse(AllowedHost.isAllowlisted("192.168.1.10"))
        assertFalse(AllowedHost.isAllowlisted("8.8.8.8"))
        assertFalse(AllowedHost.isAllowlisted("100.63.255.255"))
        assertFalse(AllowedHost.isAllowlisted("100.128.0.1"))
        assertFalse(AllowedHost.isAllowlisted("0.0.0.0"))
    }

    @Test
    fun blankSuffixFallsBackToSavedServerTailnets() {
        val saved = listOf("hp1.tailcbcc46.ts.net")
        assertTrue(
            AllowedHost.isAllowed(
                "grokbot-box.tailcbcc46.ts.net",
                debug = false,
                suffix = "",
                savedHosts = saved,
            ),
        )
        assertFalse(
            AllowedHost.isAllowed(
                "x.attacker.ts.net",
                debug = false,
                suffix = "",
                savedHosts = saved,
            ),
        )
        assertFalse(
            AllowedHost.isAllowed(
                "hp1.tailcbcc46.ts.net",
                debug = false,
                suffix = "",
                savedHosts = emptyList(),
            ),
        )
    }

    @Test
    fun releaseRefusesBlankTokenConnect() {
        assertFalse(AllowedHost.mayConnect("hp1.tailcbcc46.ts.net", "", debug = false))
        assertTrue(AllowedHost.mayConnect("hp1.tailcbcc46.ts.net", "secret-token", debug = false))
        assertFalse(AllowedHost.mayConnect("127.0.0.1", "", debug = false))
        assertTrue(AllowedHost.mayConnect("127.0.0.1", "", debug = true))
        assertFalse(AllowedHost.mayConnect("x.attacker.ts.net", "secret-token", debug = false))
    }

    @Test
    fun debugOverrideAllowsLanOnly() {
        assertTrue(AllowedHost.isAllowed("192.168.1.10", debug = true))
        assertTrue(AllowedHost.isAllowed("10.0.2.2", debug = true))
        assertTrue(AllowedHost.isAllowed("172.16.0.1", debug = true))
        assertFalse(AllowedHost.isAllowed("evil.example.com", debug = true))
        assertFalse(AllowedHost.isAllowed("x.attacker.ts.net", debug = true))
        assertFalse(AllowedHost.isAllowed("192.168.1.10", debug = false))
        assertFalse(AllowedHost.isAllowed("10.0.2.2", debug = false))
    }

    @Test
    fun defaultSuffixIsAlexTailnet() {
        assertEquals("tailcbcc46.ts.net", AllowedHost.configuredSuffix())
    }

    @Test
    fun urlDelimitersInHostFailThePin() {
        val poisoned = listOf(
            "evil.com#.tailcbcc46.ts.net",
            "evil.com?.tailcbcc46.ts.net",
            "evil.com\\.tailcbcc46.ts.net",
            "a@b.tailcbcc46.ts.net",
            "hp1.tailcbcc46.ts.net.evil.com#.tailcbcc46.ts.net",
        )
        for (host in poisoned) {
            assertFalse("isAllowed($host)", AllowedHost.isAllowed(host, debug = true))
            assertFalse("isAllowlisted($host)", AllowedHost.isAllowlisted(host))
            assertFalse("isOnPinnedTailnet($host)", AllowedHost.isOnPinnedTailnet(host))
            assertFalse("isValidHostLiteral($host)", AllowedHost.isValidHostLiteral(host))
        }
    }

    @Test
    fun percentSpaceAndNonAsciiHostsAreRejected() {
        assertFalse(AllowedHost.isValidHostLiteral("evil.com%.tailcbcc46.ts.net"))
        assertFalse(AllowedHost.isValidHostLiteral("evil.com .tailcbcc46.ts.net"))
        assertFalse(AllowedHost.isValidHostLiteral("hp1.tailcbcc46.ts.net/evil"))
        assertFalse(AllowedHost.isAllowed("hétérogène.tailcbcc46.ts.net", debug = true))
        assertTrue(AllowedHost.isValidHostLiteral("hp1.tailcbcc46.ts.net."))
        assertTrue(AllowedHost.isValidHostLiteral("HP1.TAILCBCC46.TS.NET"))
        assertTrue(AllowedHost.isAllowed("hp1.tailcbcc46.ts.net.", debug = false))
    }
}
