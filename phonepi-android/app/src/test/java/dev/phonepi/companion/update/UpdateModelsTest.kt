package dev.phonepi.companion.update

import dev.phonepi.companion.connection.ConnectEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlsTest {
    @Test
    fun loopbackHttpPathsIncludeToken() {
        val target = ConnectEndpoint.parse("127.0.0.1", "11041", "secret")
        assertEquals("http://127.0.0.1:11041/phonepi/update/manifest?token=secret", UpdateUrls.manifest(target))
        assertTrue(UpdateUrls.apk(target).endsWith("/phonepi/update/apk?token=secret"))
        assertTrue(UpdateUrls.httpBase(target).startsWith("http://"))
    }

    @Test
    fun tailscaleUsesHttps() {
        val target = ConnectEndpoint.parse("dell-mini-pc.tailcbcc46.ts.net", "443", "tok")
        assertTrue(UpdateUrls.manifest(target).startsWith("https://"))
        assertTrue(UpdateUrls.manifest(target).contains("/phonepi/update/manifest"))
        assertTrue(UpdateUrls.apk(target).contains("/phonepi/update/apk"))
    }
}

class UpdateManifestTest {
    @Test
    fun newerWhenVersionCodeGreater() {
        val remote = UpdateManifest(6, "0.2.4", "a".repeat(64), 10)
        assertTrue(remote.isNewerThan(5))
        assertFalse(remote.isNewerThan(6))
        assertFalse(remote.isNewerThan(7))
    }

    @Test
    fun parseManifestJson() {
        val parsed = UpdateEngine.parseManifest(
            """{"versionCode":6,"versionName":"0.2.4","sha256":"${"ab".repeat(32)}","size":12,"fileName":"phonepi.apk"}""",
        )
        assertEquals(6, parsed.versionCode)
        assertEquals("0.2.4", parsed.versionName)
        assertEquals(12L, parsed.size)
    }
}

class UpdateCryptoTest {
    @Test
    fun sha256IsLowerHex() {
        val hex = UpdateCrypto.sha256Hex("hi".toByteArray())
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
    }

    @Test
    fun certsMustMatchExactly() {
        val a = "cert-a".toByteArray()
        val b = "cert-b".toByteArray()
        assertTrue(UpdateCrypto.signingCertsMatch(listOf(a), listOf(a)))
        assertFalse(UpdateCrypto.signingCertsMatch(listOf(a), listOf(b)))
        assertFalse(UpdateCrypto.signingCertsMatch(emptyList(), listOf(a)))
    }
}
