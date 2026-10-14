package dev.phonepi.companion.update

sealed class UpdateVersionDecision {
    data object Allow : UpdateVersionDecision()
    data object SkipAlreadyCurrent : UpdateVersionDecision()
    data class Reject(val error: String) : UpdateVersionDecision()
}

object UpdatePolicy {
    fun decideVersion(
        installedVersionCode: Int,
        remoteVersionCode: Int,
        minVersion: Int,
        force: Boolean,
    ): UpdateVersionDecision {
        if (minVersion < 1) {
            return UpdateVersionDecision.Reject("manifest minVersion must be >= 1")
        }
        if (remoteVersionCode < minVersion) {
            return UpdateVersionDecision.Reject(
                "manifest versionCode $remoteVersionCode is below minVersion $minVersion",
            )
        }
        if (installedVersionCode < minVersion) {
            return UpdateVersionDecision.Reject(
                "This update requires PhonePi versionCode $minVersion or newer. " +
                    "Uninstall the debug build and sideload the release APK once.",
            )
        }
        if (remoteVersionCode < installedVersionCode) {
            return UpdateVersionDecision.Reject(
                "Refusing version downgrade ($remoteVersionCode < $installedVersionCode)",
            )
        }
        if (remoteVersionCode == installedVersionCode) {
            return if (force) {
                UpdateVersionDecision.Allow
            } else {
                UpdateVersionDecision.SkipAlreadyCurrent
            }
        }
        return UpdateVersionDecision.Allow
    }

    /**
     * Release builds accept only the pinned release cert. Debug builds match
     * the installed app unless a pin is configured, in which case the pin wins.
     */
    fun checkApkCert(
        apkCertSha256s: Set<String>,
        pinnedReleaseCertSha256: String,
        requirePinnedReleaseCert: Boolean,
        installedCertSha256s: Set<String>,
    ): String? {
        val apk = apkCertSha256s.map { it.lowercase() }.toSet()
        val pin = pinnedReleaseCertSha256.trim().lowercase()
        if (requirePinnedReleaseCert) {
            if (pin.length != 64) {
                return "Release cert pin is not configured"
            }
            if (pin !in apk) {
                return "APK signing certificate is not the pinned release cert"
            }
            return null
        }
        if (pin.length == 64) {
            return if (pin in apk) {
                null
            } else {
                "APK signing certificate is not the pinned release cert"
            }
        }
        val installed = installedCertSha256s.map { it.lowercase() }.toSet()
        if (installed.isEmpty() || installed != apk) {
            return "APK signing certificate does not match the installed PhonePi app"
        }
        return null
    }
}
