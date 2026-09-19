package com.audioape.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AA-016 uninstall-survival contract: app-private data (Room DB, DataStore prefs, checkpoints,
 * bookmarks, tombstones) must NEVER be included in Android full backups, cloud backups, or
 * device-to-device transfers, while user-accessible audiobook media lives outside the app and
 * survives uninstall.
 *
 * Locks the manifest + rules files so a future refactor cannot silently flip allowBackup or drop a
 * domain exclusion.
 */
class BackupExclusionContractTest {
    @Test
    fun manifestDisablesBackupAndWiresBothExclusionRuleFiles() {
        val manifest = read(manifestCandidates)
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("@xml/data_extraction_rules"))
        assertTrue(manifest.contains("@xml/backup_rules"))
    }

    @Test
    fun fullBackupContentExcludesEveryAppPrivateDomain() {
        val rules = read(rulesCandidates("backup_rules.xml"))
        DOMAINS.forEach { domain ->
            assertTrue(
                "full-backup-content must exclude domain '$domain'",
                rules.contains("<exclude domain=\"$domain\" path=\".\" />"),
            )
        }
        assertFalse(rules.contains("include")) // no opportunistic include anywhere
    }

    @Test
    fun dataExtractionRulesExcludeEveryDomainFromCloudBackupAndDeviceTransfer() {
        val rules = read(rulesCandidates("data_extraction_rules.xml"))
        assertTrue(rules.contains("<cloud-backup>"))
        assertTrue(rules.contains("<device-transfer>"))
        DOMAINS.forEach { domain ->
            assertTrue(
                "cloud-backup must exclude '$domain'",
                rules.contains("<exclude domain=\"$domain\" path=\".\" />"),
            )
        }
        // Both extraction categories combined must carry one exclude per domain (9 x 2 = 18).
        val totalExcludes = countOccurrences(rules, "<exclude domain=")
        assertTrue(
            "expected at least ${DOMAINS.size * 2} excludes across both extraction categories",
            totalExcludes >= DOMAINS.size * 2,
        )
    }

    private fun read(candidates: List<String>): String {
        val found = candidates.map(::File).firstOrNull { it.isFile }
        requireNotNull(found) { "cannot locate rules from test cwd ${File(".").absolutePath}" }
        return found.readText()
    }

    private fun rulesCandidates(resourceName: String): List<String> {
        val dir = "res/xml"
        return listOf(
            "../src/main/$dir/$resourceName",
            "src/main/$dir/$resourceName",
            "app/src/main/$dir/$resourceName",
            "../../app/src/main/$dir/$resourceName",
        )
    }

    private val manifestCandidates: List<String>
        get() =
            listOf(
                "../src/main/AndroidManifest.xml",
                "src/main/AndroidManifest.xml",
                "app/src/main/AndroidManifest.xml",
                "../../app/src/main/AndroidManifest.xml",
            )

    private fun countOccurrences(
        haystack: String,
        needle: String,
    ): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    private companion object {
        /** Every app-private persistence domain that must never reach backup/transfer. */
        val DOMAINS =
            listOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
    }
}
