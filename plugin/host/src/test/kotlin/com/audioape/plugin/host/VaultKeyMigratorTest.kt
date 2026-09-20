package com.audioape.plugin.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * AA-020c custody migration: FileBackedKeyRing-era records → a target ring (fake OS ring in
 * these JVM tests), with the no-loss + no-partial-migration guarantees of rule 7.
 */
class VaultKeyMigratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun migratesAllRecordsWithoutPlaintextLossAndDeletesMasterOnlyAfterSuccess() {
        val dir = tmp.newFolder("vault")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy, target) // writes files under `legacy` keys only

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertTrue("migration must succeed: $report", report.succeeded)
        assertEquals(2, report.migratedFiles)
        assertEquals(5, report.rekeyedRecords)
        assertTrue("master key must be deleted after full success", report.masterKeyDeleted)
        assertFalse("legacy master key file must be gone", File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())

        // Every credential now decrypts under the TARGET ring and equals the original plaintext.
        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            val records = readCiphertextFile(File(dir, "vault.$digest.bin")).records
            for (credentialId in listOf("tok", "refresh")) {
                records[credentialId]?.let { blob ->
                    val plaintext = CipherBlob.decrypt(blob, target.getOrCreatePluginKey(digest), digest)
                    assertTrue("migrated $credentialId must decrypt under the target key", plaintext != null)
                    assertEquals(secret(credentialId).toList(), plaintext!!.toList())
                }
            }
        }
    }

    @Test
    fun failureLeavesOriginalsAndMasterIntactAndProducesTypedReport() {
        val dir = tmp.newFolder("vault-fail")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy, target)

        // Tamper one record so legacy decryption fails.
        val tampered = readCiphertextFile(File(dir, "vault.$DIGEST_A.bin"))
        tampered.records["tok"] =
            CipherBlob(tampered.records["tok"]!!.iv, tampered.records["tok"]!!.ciphertext, ByteArray(16) { 0 })
        writeCiphertextFile(File(dir, "vault.$DIGEST_A.bin"), tampered.records)

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertFalse("tampered file must be reported failed", report.succeeded)
        assertTrue("failed file must be named", report.failedFiles.containsKey("vault.$DIGEST_A.bin"))
        assertFalse("master key must SURVIVE a failed migration", report.masterKeyDeleted)
        assertTrue("original master key file must still exist", File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())
        // The non-tampered plugin file must STILL be readable under the legacy key (untouched).
        val intact = readCiphertextFile(File(dir, "vault.$DIGEST_B.bin"))
        assertTrue(
            "intact plugin must remain under the legacy key",
            intact.records["tok"]!! != null,
        )
    }

    @Test
    fun revocationWorksOnTheTargetRingAfterMigration() {
        val dir = tmp.newFolder("vault-rev")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy, target)
        assertEquals("vault.migrateAll() must succeed", true, VaultKeyMigrator(dir, legacy, target).migrateAll().succeeded)

        // REAL revocation: delete the target ring key -> ciphertext no longer decrypts.
        target.removePluginKey(DIGEST_A)
        val blob = readCiphertextFile(File(dir, "vault.$DIGEST_A.bin")).records["tok"]!!
        assertTrue(
            "after OS-key revocation the ciphertext must not decrypt",
            CipherBlob.decrypt(blob, target.getOrCreatePluginKey(DIGEST_A), DIGEST_A) == null,
        )
    }

    private fun seedVault(
        dir: File,
        legacy: VaultKeyRing,
        _target: VaultKeyRing,
    ) {
        assertTrue("seed requires an existing vault dir", dir.mkdirs() || dir.exists())
        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            val records = linkedMapOf<String, CipherBlob>()
            val key = legacy.getOrCreatePluginKey(digest)
            for (credentialId in listOf("tok", "refresh")) {
                records[credentialId] = CipherBlob.encrypt(key, secret(credentialId), digest)
            }
            if (digest == DIGEST_B) records["extra"] = CipherBlob.encrypt(key, secret("extra"), digest)
            writeCiphertextFile(File(dir, "vault.$digest.bin"), records)
        }
        val master = File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME)
        Files.write(master.toPath(), ByteArray(VaultLimits.KEY_BYTES) { 7 })
    }

    private fun secret(name: String): ByteArray = name.toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
