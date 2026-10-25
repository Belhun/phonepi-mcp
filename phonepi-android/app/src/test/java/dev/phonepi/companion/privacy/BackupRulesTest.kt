package dev.phonepi.companion.privacy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupRulesTest {
    @Test
    fun deviceTransferExcludesUseDotPath() {
        val xml = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(xml.contains("<device-transfer>"))
        val transfer = xml.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue(transfer.contains("""path=".""""))
        assertTrue(transfer.contains("""domain="sharedpref""""))
    }
}
