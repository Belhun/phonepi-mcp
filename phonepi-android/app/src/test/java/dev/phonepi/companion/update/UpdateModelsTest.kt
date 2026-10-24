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
            """{"versionCode":8,"versionName":"0.2.6","sha256":"${"ab".repeat(32)}","size":12,"fileName":"phonepi.apk","minVersion":1,"signature":"${"cd".repeat(64)}"}""",
        )
        assertEquals(8, parsed.versionCode)
        assertEquals("0.2.6", parsed.versionName)
        assertEquals(12L, parsed.size)
        assertEquals(1, parsed.minVersion)
        assertEquals(128, parsed.signature.length)
    }
}

class UpdateCryptoSignatureTest {
    @Test
    fun verifiesNodeEd25519GoldenVector() {
        val manifest = UpdateManifest(
            versionCode = 8,
            versionName = "0.2.6",
            sha256 = "ab".repeat(32),
            size = 12,
            minVersion = 1,
            signature = "b27c6bef61223aa65a4bc9a762c896ea8a74baf5a9c9932a998e401167a28b5c" +
                "37a1234acb18cf8253c781a3346d020d228cbab301048f41e31d65f57b7bea0a",
        )
        val pub = "24101ab91ac7edd8e32b53680314a82b9a85ff966156f7593355a68a2e541393"
        assertEquals(null, UpdateCrypto.verifyManifestSignature(manifest, pub))
        assertEquals(
            "manifest signature is invalid",
            UpdateCrypto.verifyManifestSignature(manifest.copy(versionCode = 9), pub),
        )
        assertEquals(
            "manifest signature is invalid",
            UpdateCrypto.verifyManifestSignature(manifest.copy(signature = "ee".repeat(64)), pub),
        )
    }

    @Test
    fun missingPublicKeyIsAHardError() {
        val manifest = UpdateManifest(8, "0.2.6", "ab".repeat(32), 12, minVersion = 1, signature = "cd".repeat(64))
        assertEquals(
            "Update public key is not configured",
            UpdateCrypto.verifyManifestSignature(manifest, ""),
        )
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
