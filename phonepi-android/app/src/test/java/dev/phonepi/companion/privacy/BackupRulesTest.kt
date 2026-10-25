package dev.phonepi.companion.privacy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupRulesTest {
    @Test
    fun cloudBackupAndDeviceTransferExcludeDotPathOnPhoneAndWear() {
        val files = listOf(
            File("src/main/res/xml/data_extraction_rules.xml"),
            File("../phonepi-wear/src/main/res/xml/data_extraction_rules.xml"),
        )
        for (file in files) {
            assertTrue("missing ${file.path}", file.isFile)
            val xml = file.readText()
            for (section in listOf("cloud-backup", "device-transfer")) {
                assertTrue("${file.path} has <$section>", xml.contains("<$section>"))
                val body = xml.substringAfter("<$section>").substringBefore("</$section>")
                for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                    assertTrue(
                        "${file.path} $section excludes $domain with path=.",
                        body.contains("""<exclude domain="$domain" path="." />"""),
                    )
                }
            }
        }
    }
}
