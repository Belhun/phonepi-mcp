package dev.phonepi.companion.update

import dev.phonepi.companion.connection.ConnectEndpoint
import java.security.MessageDigest

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val fileName: String = "phonepi.apk",
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
