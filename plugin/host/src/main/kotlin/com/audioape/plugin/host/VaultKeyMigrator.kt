package com.audioape.plugin.host

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey
import kotlin.ByteArray

/**
 * One-time custody migration: file-backed master-key vault (ADR-0008 fallback) → OS Android
 * Keystore (`AndroidKeyRing`, ADR-0009 verified sequence). Runs entirely in DIGEST space:
 * each ciphertext file is named `vault.<sha256(pluginId)>.bin` and every blob's GCM AAD is the
 * digest, so no pluginId inversion is needed.
 *
 * Protocol (rule 7 — no credential loss, no partial migration):
 *   1. For each `vault.<digest>.bin`: read records (credentialId → CipherBlob).
 *   2. Decrypt each blob with the LEGACY ring key for the digest (CipherBlob.decrypt, AAD=digest).
 *   3. Re-encrypt each secret as a NEW CipherBlob under the TARGET ring key (new IV, AAD=digest).
 *   4. Write a temp records file, fsync, atomically replace `vault.<digest>.bin`.
 *   5. VERIFY every migrated record: fresh read + decrypt under the TARGET key == original.
 *   6. After ALL plugins verify: delete the legacy master key file.
 * Any failure → remove temp files, keep originals + master until the NEXT successful run.
 */
class VaultKeyMigrator(
    private val vaultDirectory: File,
    private val legacyRing: VaultKeyRing = FileBackedKeyRing(vaultDirectory),
    private val targetRing: VaultKeyRing = AndroidKeyRing(),
) {
    fun migrateAll(): MigrationReport {
        val report = MigrationReport()
        val pluginFiles = vaultDirectory.listFiles() ?: emptyArray()
        try {
            pluginFiles
                .filter { it.isFile && it.name.startsWith(PLUGIN_FILE_PREFIX) && it.name.endsWith(PLUGIN_FILE_SUFFIX) }
                .forEach { file -> migratePluginFile(file, report) }
            if (report.failedFiles.isEmpty()) {
                Files.deleteIfExists(legacyMasterKey().toPath())
                report.masterKeyDeleted = true
            }
        } catch (migrationFailure: Throwable) {
            report.migratorFailure = migrationFailure
            cleanupTemps(pluginFiles)
        }
        return report
    }

    private fun migratePluginFile(
        file: File,
        report: MigrationReport,
    ) {
        val digest =
            file.name
                .removePrefix(PLUGIN_FILE_PREFIX)
                .removeSuffix(PLUGIN_FILE_SUFFIX)
        try {
            val records = readCiphertextFile(file).records
            val legacyKey = legacyRing.getOrCreatePluginKey(digest)
            val targetKey = targetRing.getOrCreatePluginKey(digest)

            val migrated = linkedMapOf<String, CipherBlob>()
            var rekeyed = 0
            records.forEach { (credentialId, blob) ->
                val plaintext = CipherBlob.decrypt(blob, legacyKey, digest)
                require(plaintext != null) { "record $credentialId in ${file.name} failed legacy decryption" }
                migrated[credentialId] = CipherBlob.encrypt(targetKey, plaintext, digest)
                rekeyed++
            }

            val temp = File(vaultDirectory, file.name + MIGRATING_TEMP_SUFFIX)
            writeCiphertextFile(temp, migrated)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)

            // Verify: fresh decryption under the TARGET key must equal the originals.
            val verified = readCiphertextFile(file)
            records.forEach { (credentialId, original) ->
                val originalPlaintext = requireNotNull(CipherBlob.decrypt(original, legacyKey, digest))
                val migratedBlob = requireNotNull(verified.records[credentialId])
                val migratedPlaintext = CipherBlob.decrypt(migratedBlob, targetKey, digest)
                require(migratedPlaintext != null) { "record $credentialId failed post-migration decryption" }
                require(originalPlaintext.contentEquals(migratedPlaintext)) {
                    "record $credentialId plaintext mismatch after migration"
                }
            }
            report.migratedFiles += 1
            report.rekeyedRecords += rekeyed
        } catch (fileFailure: Throwable) {
            val temp = File(vaultDirectory, file.name + MIGRATING_TEMP_SUFFIX)
            Files.deleteIfExists(temp.toPath())
            report.failedFiles[file.name] = fileFailure
        }
    }

    private fun cleanupTemps(pluginFiles: Array<File>) {
        pluginFiles
            .filter { it.name.endsWith(MIGRATING_TEMP_SUFFIX) }
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun legacyMasterKey(): File = File(vaultDirectory, FileBackedKeyRing.MASTER_KEY_FILE_NAME)

    private companion object {
        const val PLUGIN_FILE_PREFIX = "vault."
        const val PLUGIN_FILE_SUFFIX = ".bin"
        const val MIGRATING_TEMP_SUFFIX = ".tmp-migrating"
    }
}

/** Aggregate result of one migration pass. */
data class MigrationReport(
    var migratedFiles: Int = 0,
    var rekeyedRecords: Int = 0,
    var masterKeyDeleted: Boolean = false,
    var failedFiles: MutableMap<String, Throwable> = linkedMapOf(),
    var migratorFailure: Throwable? = null,
) {
    val succeeded: Boolean
        get() = failedFiles.isEmpty() && migratorFailure == null
}
