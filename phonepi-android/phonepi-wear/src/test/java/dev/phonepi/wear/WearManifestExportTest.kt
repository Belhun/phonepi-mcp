package dev.phonepi.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WearManifestExportTest {
    @Test
    fun confirmAndNextAlarmAreExportedForTileLaunchAction() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        assertTrue(xml.contains("android:name=\".ConfirmActionActivity\""))
        assertTrue(xml.contains("android:name=\".NextAlarmActivity\""))
        val confirm = activityBlock(xml, ".ConfirmActionActivity")
        val alarm = activityBlock(xml, ".NextAlarmActivity")
        assertTrue(confirm.contains("android:exported=\"true\""))
        assertTrue(alarm.contains("android:exported=\"true\""))
        assertFalse(confirm.contains("android:exported=\"false\""))
        assertFalse(alarm.contains("android:exported=\"false\""))
        assertFalse(confirm.contains("android:permission="))
        assertFalse(alarm.contains("android:permission="))
    }

    @Test
    fun confirmGuardIgnoresSkipExtrasAndRequiresKnownKind() {
        assertTrue(ConfirmActionGuard.ignoresSkipExtras())
        assertTrue(ConfirmActionGuard.SKIP_EXTRA_KEYS.contains("skipConfirm"))
        assertTrue(ConfirmActionGuard.SKIP_EXTRA_KEYS.contains("auto"))
        assertTrue(ConfirmActionGuard.SKIP_EXTRA_KEYS.contains("confirmed"))
        assertTrue(ConfirmActionGuard.isKnownKind(ConfirmActionActivity.KIND_QUIET))
        assertTrue(ConfirmActionGuard.isKnownKind(ConfirmActionActivity.KIND_IM_OK))
        assertFalse(ConfirmActionGuard.isKnownKind("send"))
        assertFalse(ConfirmActionGuard.isKnownKind(""))
    }

    private fun activityBlock(xml: String, name: String): String {
        val start = xml.indexOf("""android:name="$name"""")
        assertTrue("missing $name", start >= 0)
        val open = xml.lastIndexOf("<activity", start)
        val closeTag = xml.indexOf("</activity>", start)
        val selfClose = xml.indexOf("/>", start)
        val end = when {
            closeTag >= 0 && (selfClose < 0 || closeTag < selfClose) -> closeTag
            selfClose >= 0 -> selfClose + 2
            else -> -1
        }
        assertTrue("unclosed $name", end > open)
        return xml.substring(open, end)
    }
}
