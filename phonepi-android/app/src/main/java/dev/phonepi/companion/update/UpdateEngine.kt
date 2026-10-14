package dev.phonepi.companion.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.phonepi.companion.BuildConfig
import dev.phonepi.companion.connection.ConnectEndpoint
import dev.phonepi.companion.connection.ConnectionPrefs
import dev.phonepi.companion.connection.TailscaleDns
import dev.phonepi.companion.connection.TailscaleSocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateRunResult(
    val promptShown: Boolean,
    val method: String,
    val downloaded: Boolean,
    val verified: Boolean,
    val currentVersionCode: Int,
    val serverVersionCode: Int?,
    val serverVersionName: String?,
    val notificationPosted: Boolean = false,
    val overlayGranted: Boolean = false,
    val inForeground: Boolean = false,
    val grantNeeded: String? = null,
    val skipped: Boolean = false,
    val error: String? = null,
)

class UpdateEngine(context: Context) {
    private val app = context.applicationContext
    private val client = OkHttpClient.Builder()
        .dns(TailscaleDns)
        .socketFactory(TailscaleSocketFactory())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun installedVersionCode(): Int = BuildConfig.VERSION_CODE

    fun installedVersionName(): String = BuildConfig.VERSION_NAME

    fun endpoint(): ConnectEndpoint.Target? {
        val saved = ConnectionPrefs.getLastSuccessful(app) ?: return null
        return ConnectEndpoint.parse(saved.host, saved.port.toString(), saved.wsToken)
    }

    suspend fun fetchManifest(): UpdateManifest {
        val target = endpoint() ?: error("Not connected — set host and Connect first")
        val request = Request.Builder()
            .url(UpdateUrls.manifest(target))
            .header("Authorization", "Bearer ${target.wsToken}")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(parseError(body, response.code))
                }
                parseManifest(body)
            }
        }
    }

    suspend fun run(
        force: Boolean,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): UpdateRunResult {
        val current = installedVersionCode()
        val manifest = try {
            fetchManifest()
        } catch (e: Exception) {
            return UpdateRunResult(
                promptShown = false,
                method = "none",
                downloaded = false,
                verified = false,
                currentVersionCode = current,
                serverVersionCode = null,
                serverVersionName = null,
                error = e.message ?: "Could not read update manifest",
            )
        }
        if (!force && !manifest.isNewerThan(current)) {
            return UpdateRunResult(
                promptShown = false,
                method = "none",
                downloaded = false,
                verified = false,
                currentVersionCode = current,
                serverVersionCode = manifest.versionCode,
                serverVersionName = manifest.versionName,
                skipped = true,
            )
        }
        val apk = try {
            downloadAndVerify(manifest, onProgress)
        } catch (e: Exception) {
            return UpdateRunResult(
                promptShown = false,
                method = "none",
                downloaded = false,
                verified = false,
                currentVersionCode = current,
                serverVersionCode = manifest.versionCode,
                serverVersionName = manifest.versionName,
                error = e.message ?: "Download failed",
            )
        }
        if (!UnknownAppInstall.isGranted(app)) {
            return UpdateRunResult(
                promptShown = false,
                method = "none",
                downloaded = true,
                verified = true,
                currentVersionCode = current,
                serverVersionCode = manifest.versionCode,
                serverVersionName = manifest.versionName,
                grantNeeded = "Install unknown apps (Settings → Apps → PhonePi → Install unknown apps)",
                error = "Grant Install unknown apps for PhonePi, then tap Install. PhonePi never installs silently.",
            )
        }
        val prompt = withContext(Dispatchers.Main) {
            startInstaller(apk, manifest.size)
        }
        return UpdateRunResult(
            promptShown = prompt.promptShown,
            method = prompt.method,
            downloaded = true,
            verified = true,
            currentVersionCode = current,
            serverVersionCode = manifest.versionCode,
            serverVersionName = manifest.versionName,
            notificationPosted = prompt.notificationPosted,
            overlayGranted = prompt.overlayGranted,
            inForeground = prompt.inForeground,
            grantNeeded = prompt.grantNeeded,
            error = prompt.error,
        )
    }

    private suspend fun downloadAndVerify(
        manifest: UpdateManifest,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = endpoint() ?: error("Not connected")
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "phonepi-update.apk")
        if (file.exists()) file.delete()
        val request = Request.Builder()
            .url(UpdateUrls.apk(target))
            .header("Authorization", "Bearer ${target.wsToken}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error(parseError(response.body?.string().orEmpty(), response.code))
            }
            val body = response.body ?: error("Empty APK response")
            val total = if (manifest.size > 0) manifest.size else body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { b -> "%02x".format(b) }
            if (hex != manifest.sha256.lowercase()) {
                file.delete()
                error("APK sha256 mismatch (got $hex, expected ${manifest.sha256})")
            }
            if (manifest.size > 0 && received != manifest.size) {
                file.delete()
                error("APK size mismatch (got $received, expected ${manifest.size})")
            }
        }
        verifyPackage(file)
        file
    }

    private fun verifyPackage(file: File) {
        val pm = app.packageManager
        val info = archiveInfo(pm, file) ?: error("APK is not a readable package")
        if (info.packageName != app.packageName) {
            error("APK package ${info.packageName} is not ${app.packageName}")
        }
        val installed = installedSigners()
        val apk = archiveSigners(info)
        if (!UpdateCrypto.signingCertsMatch(installed, apk)) {
            error("APK signing certificate does not match the installed PhonePi app")
        }
    }

    private fun archiveInfo(pm: PackageManager, file: File): android.content.pm.PackageInfo? {
        val flags = signingFlags()
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        } ?: return null
        info.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        return info
    }

    private fun installedSigners(): List<ByteArray> {
        val pm = app.packageManager
        val flags = signingFlags()
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, flags)
        }
        return signersOf(info)
    }

    private fun archiveSigners(info: android.content.pm.PackageInfo): List<ByteArray> = signersOf(info)

    @Suppress("DEPRECATION")
    private fun signersOf(info: android.content.pm.PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptyList()
            val sigs = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            return sigs?.map { it.toByteArray() }.orEmpty()
        }
        return info.signatures?.map { it.toByteArray() }.orEmpty()
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private suspend fun startInstaller(apk: File, size: Long): UpdatePromptResult {
        val confirm = try {
            commitPackageSession(apk, size) ?: viewApkIntent(apk)
        } catch (_: Exception) {
            viewApkIntent(apk)
        }
        if (confirm == null) {
            return UpdatePromptResult(
                promptShown = false,
                method = "none",
                notificationPosted = false,
                overlayGranted = false,
                inForeground = false,
                error = "System installer did not return a prompt. Grant Install unknown apps and retry.",
            )
        }
        return UpdatePromptLauncher.showInstaller(app, confirm)
    }

    private suspend fun commitPackageSession(apk: File, size: Long): Intent? {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("phonepi", 0, size.coerceAtLeast(apk.length())).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val deferred = CompletableDeferred<Intent?>()
            InstallStatusGate.pending = deferred
            val statusIntent = Intent(app, InstallStatusReceiver::class.java)
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_MUTABLE
            val pending = android.app.PendingIntent.getBroadcast(app, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
            session.close()
            return withTimeoutOrNull(15_000) { deferred.await() }
        } catch (e: Exception) {
            try {
                session.abandon()
            } catch (_: Exception) {
            }
            throw e
        } finally {
            InstallStatusGate.pending = null
        }
    }

    private fun viewApkIntent(apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseManifest(raw: String): UpdateManifest {
            val obj = json.parseToJsonElement(raw).jsonObject
            val versionCode = obj["versionCode"]?.jsonPrimitive?.int
                ?: error("manifest missing versionCode")
            val versionName = obj["versionName"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (versionName.isEmpty()) error("manifest missing versionName")
            val sha256 = obj["sha256"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            if (sha256.length != 64) error("manifest missing sha256")
            val size = obj["size"]?.jsonPrimitive?.long
                ?: error("manifest missing size")
            val fileName = obj["fileName"]?.jsonPrimitive?.content ?: "phonepi.apk"
            return UpdateManifest(versionCode, versionName, sha256, size, fileName)
        }

        fun parseError(body: String, code: Int): String {
            return try {
                val obj = json.parseToJsonElement(body).jsonObject
                obj["error"]?.jsonPrimitive?.content ?: "Update HTTP $code"
            } catch (_: Exception) {
                if (body.isNotBlank()) body.take(180) else "Update HTTP $code"
            }
        }
    }
}
