package com.smugview.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R-54: allowBackup=false does not stop Android 12+ device-to-device transfer; dataExtractionRules does. */
class ManifestGuardTest {
    private val main = File("src/main")

    @Test
    fun manifest_pointsAtDataExtractionRules() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun rules_excludeEveryDomainFromBackupAndTransfer() {
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue("$section must exclude $domain", body.contains("<exclude domain=\"$domain\""))
            }
        }
    }
}
