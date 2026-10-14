package dev.phonepi.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun forceAllowsSameVersionButNeverADowngrade() {
        assertEquals(
            UpdateVersionDecision.Allow,
            UpdatePolicy.decideVersion(8, 8, minVersion = 1, force = true),
        )
        assertEquals(
            UpdateVersionDecision.SkipAlreadyCurrent,
            UpdatePolicy.decideVersion(8, 8, minVersion = 1, force = false),
        )
        val down = UpdatePolicy.decideVersion(8, 7, minVersion = 1, force = true)
        assertTrue(down is UpdateVersionDecision.Reject)
        assertTrue((down as UpdateVersionDecision.Reject).error.contains("downgrade"))
    }

    @Test
    fun newerIsAllowed() {
        assertEquals(
            UpdateVersionDecision.Allow,
            UpdatePolicy.decideVersion(7, 8, minVersion = 1, force = false),
        )
    }

    @Test
    fun minVersionBlocksOldInstalledApps() {
        val blocked = UpdatePolicy.decideVersion(7, 9, minVersion = 8, force = false)
        assertTrue(blocked is UpdateVersionDecision.Reject)
        assertTrue((blocked as UpdateVersionDecision.Reject).error.contains("8"))
    }

    @Test
    fun releasePinRejectsOtherCertsIncludingDebug() {
        val pin = "aa".repeat(32)
        val debug = "bb".repeat(32)
        assertNull(
            UpdatePolicy.checkApkCert(
                apkCertSha256s = setOf(pin),
                pinnedReleaseCertSha256 = pin,
                requirePinnedReleaseCert = true,
                installedCertSha256s = setOf(debug),
            ),
        )
        val rejected = UpdatePolicy.checkApkCert(
            apkCertSha256s = setOf(debug),
            pinnedReleaseCertSha256 = pin,
            requirePinnedReleaseCert = true,
            installedCertSha256s = setOf(debug),
        )
        assertEquals("APK signing certificate is not the pinned release cert", rejected)
    }

    @Test
    fun releaseWithoutPinRefusesToInstall() {
        val err = UpdatePolicy.checkApkCert(
            apkCertSha256s = setOf("aa".repeat(32)),
            pinnedReleaseCertSha256 = "",
            requirePinnedReleaseCert = true,
            installedCertSha256s = setOf("aa".repeat(32)),
        )
        assertEquals("Release cert pin is not configured", err)
    }

    @Test
    fun debugWithoutPinStillMatchesInstalledCert() {
        val cert = "cc".repeat(32)
        assertNull(
            UpdatePolicy.checkApkCert(
                apkCertSha256s = setOf(cert),
                pinnedReleaseCertSha256 = "",
                requirePinnedReleaseCert = false,
                installedCertSha256s = setOf(cert),
            ),
        )
    }
}
