package com.botati.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Spec §12/§14: the vault stays out of backup *and* out of device-to-device
 * transfer, and the rules are wired into the manifest. Since the 2026-10-25
 * owner decision (Q3) the Room database is excluded the same way.
 *
 * This is a static regression test, not a device test: it reads the shipped XML
 * and fails if an exclusion is dropped. It is the test §14 asks for on the
 * "vault stays outside Backup" clause.
 */
class VaultBackupRulesTest {

    private val ciphertext = """domain="file" path="datastore/vault.preferences_pb""""
    private val keysetPrefs = """domain="sharedpref" path="botati_vault_keyset_prefs.xml""""
    // Owner decision 2026-10-25 (Q3): the Room database stays out of backup
    // and D2D in full — mail content and the audit log outrank moving chats
    // between devices. WAL siblings included: recent writes live in -wal.
    private val dbFile = """domain="database" path="botati.db""""
    private val dbWal = """domain="database" path="botati.db-wal""""
    private val dbShm = """domain="database" path="botati.db-shm""""

    private fun findFile(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val base = dir ?: return null
            for (prefix in listOf("", "app/")) {
                val f = File(base, prefix + relative)
                if (f.isFile) return f
            }
            dir = base.parentFile
        }
        return null
    }

    private fun block(xml: String, tag: String): String =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?: error("missing <$tag> in rules")

    @Test fun manifestReferencesBothRuleSets() {
        val manifest = findFile("src/main/AndroidManifest.xml")
        assumeTrue("manifest not found from ${System.getProperty("user.dir")}", manifest != null)
        val text = manifest!!.readText()
        assertTrue(
            "dataExtractionRules (API 31+) must be wired in",
            text.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""),
        )
        assertTrue(
            "fullBackupContent (API <= 30) must be wired in",
            text.contains("""android:fullBackupContent="@xml/backup_rules""""),
        )
    }

    @Test fun dataExtractionRulesExcludeCiphertextAndKeysetFromCloudBackup() {
        val f = findFile("src/main/res/xml/data_extraction_rules.xml")
        assumeTrue("rules not found", f != null)
        val cloud = block(f!!.readText(), "cloud-backup")
        assertTrue("ciphertext must not be cloud-backed-up", cloud.contains(ciphertext))
        assertTrue("keyset prefs must not be cloud-backed-up", cloud.contains(keysetPrefs))
    }

    @Test fun dataExtractionRulesExcludeCiphertextAndKeysetFromDeviceTransfer() {
        val f = findFile("src/main/res/xml/data_extraction_rules.xml")
        assumeTrue("rules not found", f != null)
        val transfer = block(f!!.readText(), "device-transfer")
        assertTrue("ciphertext must not migrate over D2D", transfer.contains(ciphertext))
        assertTrue("keyset prefs must not migrate over D2D", transfer.contains(keysetPrefs))
    }

    @Test fun legacyFullBackupContentExcludesBothArtifacts() {
        val f = findFile("src/main/res/xml/backup_rules.xml")
        assumeTrue("legacy rules not found", f != null)
        val text = f!!.readText()
        assertTrue(text.contains(ciphertext))
        assertTrue(text.contains(keysetPrefs))
        assertFalse("legacy file must not carry the new-format root", text.contains("<data-extraction-rules>"))
    }

    @Test fun dataExtractionRulesExcludeDatabaseFromCloudBackup() {
        val f = findFile("src/main/res/xml/data_extraction_rules.xml")
        assumeTrue("rules not found", f != null)
        val cloud = block(f!!.readText(), "cloud-backup")
        assertTrue("database must not be cloud-backed-up", cloud.contains(dbFile))
        assertTrue("WAL file must not be cloud-backed-up", cloud.contains(dbWal))
        assertTrue("SHM file must not be cloud-backed-up", cloud.contains(dbShm))
    }

    @Test fun dataExtractionRulesExcludeDatabaseFromDeviceTransfer() {
        val f = findFile("src/main/res/xml/data_extraction_rules.xml")
        assumeTrue("rules not found", f != null)
        val transfer = block(f!!.readText(), "device-transfer")
        assertTrue("database must not migrate over D2D", transfer.contains(dbFile))
        assertTrue("WAL file must not migrate over D2D", transfer.contains(dbWal))
        assertTrue("SHM file must not migrate over D2D", transfer.contains(dbShm))
    }

    @Test fun legacyFullBackupContentExcludesDatabase() {
        val f = findFile("src/main/res/xml/backup_rules.xml")
        assumeTrue("legacy rules not found", f != null)
        val text = f!!.readText()
        assertTrue(text.contains(dbFile))
        assertTrue(text.contains(dbWal))
        assertTrue(text.contains(dbShm))
    }
}
