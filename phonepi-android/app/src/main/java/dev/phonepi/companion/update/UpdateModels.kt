package dev.phonepi.companion.update

import dev.phonepi.companion.connection.ConnectEndpoint
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val fileName: String = "phonepi.apk",
    val minVersion: Int = 1,
    val signature: String = "",
) {
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode
}

object UpdateUrls {
    const val MANIFEST_PATH = "/phonepi/update/manifest"
    const val APK_PATH = "/phonepi/update/apk"

    fun httpBase(target: ConnectEndpoint.Target): String {
        val tls = target.url.startsWith("wss://")
        val scheme = if (tls) "https" else "http"
        return "$scheme://${target.host}:${target.port}"
    }

    fun manifest(target: ConnectEndpoint.Target): String =
        withToken(httpBase(target) + MANIFEST_PATH, target.wsToken)

    fun apk(target: ConnectEndpoint.Target): String =
        withToken(httpBase(target) + APK_PATH, target.wsToken)

    private fun withToken(url: String, token: String): String {
        if (token.isBlank()) return url
        return "$url?token=${java.net.URLEncoder.encode(token, Charsets.UTF_8.name())}"
    }
}

object UpdateCrypto {
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun signingCertsMatch(installed: List<ByteArray>, apk: List<ByteArray>): Boolean {
        val a = installed.map { sha256Hex(it) }.toSet()
        val b = apk.map { sha256Hex(it) }.toSet()
        return a.isNotEmpty() && a == b
    }

    /** Keep in lockstep with server/src/update-sign.ts canonicalUpdatePayload. */
    fun signedPayload(manifest: UpdateManifest): ByteArray {
        val sha = manifest.sha256.trim().lowercase()
        val name = manifest.versionName.trim()
        return "v1\n${manifest.versionCode}\n$name\n$sha\n${manifest.minVersion}\n"
            .toByteArray(Charsets.UTF_8)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        require(clean.length % 2 == 0) { "hex length must be even" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun verifyManifestSignature(manifest: UpdateManifest, publicKeyHex: String): String? {
        val pub = publicKeyHex.trim().lowercase()
        val sig = manifest.signature.trim().lowercase()
        if (pub.length != 64) {
            return "Update public key is not configured"
        }
        if (sig.length != 128) {
            return "manifest missing Ed25519 signature"
        }
        return try {
            val spec = EdDSANamedCurveTable.getByName("Ed25519")
                ?: return "Ed25519 curve unavailable"
            val publicKey = EdDSAPublicKey(EdDSAPublicKeySpec(hexToBytes(pub), spec))
            val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
            engine.initVerify(publicKey)
            engine.update(signedPayload(manifest))
            if (engine.verify(hexToBytes(sig))) {
                null
            } else {
                "manifest signature is invalid"
            }
        } catch (_: Exception) {
            "manifest signature is invalid"
        }
    }
}

data class UpdatePromptResult(
    val promptShown: Boolean,
    val method: String,
    val notificationPosted: Boolean,
    val overlayGranted: Boolean,
    val inForeground: Boolean,
    val grantNeeded: String? = null,
    val error: String? = null,
)
