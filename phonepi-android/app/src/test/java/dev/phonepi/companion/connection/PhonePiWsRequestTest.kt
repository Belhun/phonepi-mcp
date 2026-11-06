package dev.phonepi.companion.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePiWsRequestTest {
    @Test
    fun upgradeRequestSendsAuthorizationAndNoQueryToken() {
        val target = ConnectEndpoint.parse("dell-mini-pc.tailcbcc46.ts.net", "443", "secret-token")
        val request = phonePiWsRequest(target)
        val url = request.url.toString()
        assertTrue(url.contains("dell-mini-pc.tailcbcc46.ts.net"))
        assertTrue(url.contains("/phonepi"))
        assertFalse(url.contains("token="))
        assertFalse(url.contains("secret-token"))
        assertEquals("Bearer secret-token", request.header("Authorization"))
    }

    @Test
    fun blankTokenOmitsAuthorization() {
        val target = ConnectEndpoint.parse("127.0.0.1", "7000", "")
        val request = phonePiWsRequest(target)
        val url = request.url.toString()
        assertTrue(url.contains("127.0.0.1"))
        assertTrue(url.contains("/phonepi"))
        assertFalse(url.contains("token="))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun nonAllowlistedHostOmitsAuthorization() {
        val target = ConnectEndpoint.parse("evil.example", "443", "secret-token")
        val request = phonePiWsRequest(target)
        assertNull(request.header("Authorization"))
        assertFalse(request.url.toString().contains("token="))
        assertFalse(request.url.toString().contains("secret-token"))
    }

    @Test
    fun otherTailnetOmitsAuthorization() {
        val target = ConnectEndpoint.parse("x.attacker.ts.net", "443", "secret-token")
        val request = phonePiWsRequest(target)
        assertNull(request.header("Authorization"))
        assertFalse(request.url.toString().contains("secret-token"))
    }

    @Test
    fun delimiterHostOmitsAuthorizationAndIsRefused() {
        val hosts = listOf(
            "evil.com#.tailcbcc46.ts.net",
            "evil.com?.tailcbcc46.ts.net",
            "evil.com\\.tailcbcc46.ts.net",
            "a@b.tailcbcc46.ts.net",
            "hp1.tailcbcc46.ts.net.evil.com#.tailcbcc46.ts.net",
        )
        for (host in hosts) {
            val target = ConnectEndpoint.parse(host, "443", "attacker-token-at-least-32-chars-long")
            val request = phonePiWsRequest(target)
            assertNull("Authorization for $host", request.header("Authorization"))
            assertFalse(request.url.toString().contains("attacker-token"))
            assertFalse("requestMatchesTarget($host)", requestMatchesTarget(request, target.host, target.port))
        }
    }

    @Test
    fun realTailnetHostMatchesOkHttpHostAndKeepsAuthorization() {
        val target = ConnectEndpoint.parse("hp1.tailcbcc46.ts.net", "443", "secret-token")
        val request = phonePiWsRequest(target)
        assertEquals("hp1.tailcbcc46.ts.net", request.url.host)
        assertEquals(443, request.url.port)
        assertTrue(requestMatchesTarget(request, target.host, target.port))
        assertEquals("Bearer secret-token", request.header("Authorization"))
    }
}
