package com.audioape.plugin.host

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey

/**
 * One-time custody migration: file-backed master-key vault (ADR-0008 fallback) → OS Android
 * Keystore (`AndroidKeyRing`, ADR-0009 verified sequence). Runs entirely in DIGEST space:
 * each ciphertext file is named `vault.<sha256(pluginId)>.bin` and every blob's GCM AAD is the
 * digest, so no pluginId inversion is needed.
 *
 * Retry-safe + crash-safe protocol (per plugin file, one at a time):
 *
 *   journal state: PENDING → (decrypt legacy → re-encrypt OS → verify TEMP)
 *                  → REPLACED_UNVERIFIED (written AHEAD of the file swap) → verify FINAL
 *                  → DONE → backup deleted.
 *
 *   1. DONE files are SKIPPED on every run (never re-decrypted — this is what makes a retry
 *      after a PARTIAL migration lossless: a file that already moved to OS custody is never
 *      touched with the legacy key again).
 *   2. Every file's ORIGINAL is preserved as `*.bak-migrating` until its new file has been
 *      VERIFIED (decrypt-all under the OS key, plaintext equal to the originals). A crash at
 *      ANY point is repaired from the journal + backup, not by guessing.
 *   3. The journal is re-written atomically (temp + rename) BEFORE each state change, so a torn
 *      journal write is impossible (crash -> old or new state, never garbage).
 *   4. Only when ALL files are DONE is the legacy `vault-master.key` deleted.
 *
 * Recovery rules at entry (per file, executed before any decryption):
 *   - backup present + FINAL present + FINAL decrypts under OS key            -> mark DONE, drop backup
 *   - backup present + (FINAL missing OR FINAL does NOT decrypt under OS key) -> restore backup to FINAL, re-run
 *   - backup absent                                                           -> normal path from current FINAL
 */
class VaultKeyMigrator(
    private val vaultDirectory: File,
    private val legacyRing: VaultKeyRing = FileBackedKeyRing(vaultDirectory),
    private val targetRing: VaultKeyRing = AndroidKeyRing(),
) {
    private val journal = MigrationJournal(File(vaultDirectory, JOURNAL_FILE_NAME))
    private val originalsByDigest = linkedMapOf<String, MutableMap<String, ByteArray>>()

    fun migrateAll(): MigrationReport {
        val report = MigrationReport()
        val allFiles = vaultDirectory.listFiles() ?: emptyArray()
        try {
            // Plugin identity = every digest with a FINAL file OR a leftover BACKUP. A file whose
            // swap crashed mid-way has its FINAL missing and its ORIGINAL in the backup — if we
            // only looked at `*.bin` we would silently ignore it and delete the master key while
            // its credentials sat unrecovered in the backup (data-loss bug caught by tests).
            val finals = allFiles.filter { it.isFile && it.name.startsWith(PLUGIN_FILE_PREFIX) && it.name.endsWith(PLUGIN_FILE_SUFFIX) }
            val backups = allFiles.filter { it.isFile && it.name.startsWith(PLUGIN_FILE_PREFIX) && it.name.endsWith(BACKUP_SUFFIX) }
            val identities = (finals.map { it.name } + backups.map { it.name.removeSuffix(BACKUP_SUFFIX) }).distinct()
            identities
                .map { name -> allFiles.firstOrNull { it.name == name } ?: File(vaultDirectory, name) }
                .forEach { file -> migratePluginFile(file, report) }
            val unfinished = journal.pendingDigests(identityFiles())
            if (report.failedFiles.isEmpty() && unfinished.isEmpty()) {
                val master = File(vaultDirectory, FileBackedKeyRing.MASTER_KEY_FILE_NAME)
                if (Files.deleteIfExists(master.toPath())) {
                    report.masterKeyDeleted = true
                }
                cleanupTemps(allFiles)
                journal.discard()
            }
        } catch (migrationFailure: Throwable) {
            report.migratorFailure = migrationFailure
            // Journal + backups remain for the next run's recovery; only stray temps are cleared.
            cleanupTemps(allFiles)
        }
        return report
    }

    /** Current final/backup identities, used to judge whether any non-DONE state is still live. */
    private fun identityFiles(): Array<File> {
        val all = vaultDirectory.listFiles() ?: emptyArray()
        return all
            .filter { it.name.startsWith(PLUGIN_FILE_PREFIX) && (it.name.endsWith(PLUGIN_FILE_SUFFIX) || it.name.endsWith(BACKUP_SUFFIX)) }
            .toTypedArray()
    }

    private fun migratePluginFile(
        file: File,
        report: MigrationReport,
    ) {
        val digest =
            file.name
                .removePrefix(PLUGIN_FILE_PREFIX)
                .removeSuffix(PLUGIN_FILE_SUFFIX)
        val backup = File(vaultDirectory, file.name + BACKUP_SUFFIX)
        try {
            when (journal.stateOf(digest)) {
                JournalState.DONE -> {
                    return
                }

                // never re-decrypt an already-migrated file
                JournalState.REPLACED_UNVERIFIED -> {
                    if (backup.exists()) {
                        if (verifyUnderTarget(file, digest)) {
                            journal.markDone(digest)
                            Files.deleteIfExists(backup.toPath())
                        } else {
                            restoreBackup(file, backup, digest)
                        }
                    } // backup absent: plain PENDING-style replay from current FINAL
                }

                else -> {
                    Unit
                }
            }
            if (journal.stateOf(digest) != JournalState.DONE && backup.exists()) {
                // Backup lingering while PENDING (crash before/during the swap): restore and replay.
                if (!verifyUnderTarget(file, digest)) {
                    restoreBackup(file, backup, digest)
                }
            }
            if (journal.stateOf(digest) == JournalState.DONE) return

            val records = readCiphertextFile(file).records
            val legacyKey = legacyRing.getOrCreatePluginKey(digest)
            val targetKey = targetRing.getOrCreatePluginKey(digest)
            val originals = linkedMapOf<String, ByteArray>()
            records.forEach { (credentialId, blob) ->
                val plaintext = CipherBlob.decrypt(blob, legacyKey, digest)
                require(plaintext != null) { "record $credentialId in ${file.name} failed legacy decryption" }
                originals[credentialId] = plaintext
            }
            originalsByDigest[digest] = originals

            // Build + verify the TEMP file BEFORE touching the real one.
            val migrated = linkedMapOf<String, CipherBlob>()
            originals.forEach { (credentialId, plaintext) ->
                migrated[credentialId] = CipherBlob.encrypt(targetKey, plaintext, digest)
            }
            val temp = File(vaultDirectory, file.name + MIGRATING_TEMP_SUFFIX)
            writeCiphertextFile(temp, migrated)
            verifyRecords(temp, targetKey, digest, originals) // throws on any mismatch

            // Swap with write-ahead journal state + backup of the original.
            journal.markReplacedUnverified(digest)
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)

            verifyRecords(file, targetKey, digest, originals) // post-swap; throws -> restore below
            journal.markDone(digest)
            Files.deleteIfExists(backup.toPath())

            report.migratedFiles += 1
            report.rekeyedRecords += originals.size
        } catch (fileFailure: Throwable) {
            if (backup.exists() && journal.stateOf(digest) != JournalState.DONE) {
                restoreBackup(file, backup, digest)
            }
            Files.deleteIfExists(File(vaultDirectory, file.name + MIGRATING_TEMP_SUFFIX).toPath())
            journal.markPending(digest)
            report.failedFiles[file.name] = fileFailure
        }
    }

    /** All-or-nothing restore: backup wins over a half-swapped FINAL. */
    private fun restoreBackup(
        file: File,
        backup: File,
        digest: String,
    ) {
        if (backup.exists()) {
            Files.move(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        journal.markPending(digest)
    }

    /**
     * Decrypts every record under [key] and checks byte equality against [expected].
     * Throws (aborting the file) if any record is missing, undecryptable, or different.
     */
    private fun verifyRecords(
        file: File,
        key: SecretKey,
        digest: String,
        expected: Map<String, ByteArray>,
    ) {
        val verified = readCiphertextFile(file).records
        require(verified.keys == expected.keys) {
            "migration verification: record set mismatch for ${file.name} (${verified.keys} vs ${expected.keys})"
        }
        expected.forEach { (credentialId, plaintext) ->
            val decrypted = CipherBlob.decrypt(verified[credentialId]!!, key, digest)
            require(decrypted != null && decrypted.contentEquals(plaintext)) {
                "migration verification: record $credentialId mismatch in ${file.name}"
            }
        }
    }

    /**
     * Recovery-only sanity check: every record in [file] decrypts under the TARGET ring. True
     * plaintext-equality was enforced when the TEMP was verified pre-swap in the run that wrote
     * it; a crash between swap and DONE can only be re-verified for decryptability here.
     */
    private fun verifyUnderTarget(
        file: File,
        digest: String,
    ): Boolean =
        try {
            val key = targetRing.getOrCreatePluginKey(digest)
            val records = readCiphertextFile(file).records
            records.isNotEmpty() && records.values.all { CipherBlob.decrypt(it, key, digest) != null }
        } catch (_: Throwable) {
            false
        }

    private fun cleanupTemps(pluginFiles: Array<File>) {
        pluginFiles
            .filter { it.name.endsWith(MIGRATING_TEMP_SUFFIX) }
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private companion object {
        const val JOURNAL_FILE_NAME = "migration.journal"
    }
}

private const val PLUGIN_FILE_PREFIX = "vault."
private const val PLUGIN_FILE_SUFFIX = ".bin"
private const val MIGRATING_TEMP_SUFFIX = ".tmp-migrating"
private const val BACKUP_SUFFIX = ".bak-migrating"

internal enum class JournalState { PENDING, REPLACED_UNVERIFIED, DONE }

/**
 * Write-ahead migration journal. Atomic rewrites (temp + rename) so a crash can never leave a
 * torn state line; states recorded BEFORE the file operations they guard.
 */
internal class MigrationJournal(
    private val file: File,
) {
    private val states = linkedMapOf<String, JournalState>()

    init {
        if (file.exists()) {
            file
                .readLines()
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0] to JournalState.entries.firstOrNull { it.name == parts[1].trim() }
                    } else {
                        null
                    }
                }.filter { it.second != null }
                .forEach { (digest, state) -> states[digest] = state!! }
        }
    }

    fun stateOf(digest: String): JournalState = states[digest] ?: JournalState.PENDING

    fun markReplacedUnverified(digest: String) = set(digest, JournalState.REPLACED_UNVERIFIED)

    fun markDone(digest: String) = set(digest, JournalState.DONE)

    fun markPending(digest: String) = set(digest, JournalState.PENDING)

    /** Digests with a non-terminal state whose final OR backup file is still live. */
    fun pendingDigests(pluginFiles: Array<File>): List<String> =
        states
            .filterValues { it != JournalState.DONE }
            .keys
            .filter { digest ->
                pluginFiles.any {
                    it.name == "$PLUGIN_FILE_PREFIX$digest$PLUGIN_FILE_SUFFIX" ||
                        it.name == "$PLUGIN_FILE_PREFIX$digest$PLUGIN_FILE_SUFFIX$BACKUP_SUFFIX"
                }
            }

    private fun set(
        digest: String,
        state: JournalState,
    ) {
        states[digest] = state
        val temp = File(file.parentFile, file.name + ".tmp")
        Files.write(
            temp.toPath(),
            states.entries.joinToString("\n") { "${it.key}=${it.value.name}" }.toByteArray(StandardCharsets.UTF_8),
        )
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun discard() {
        states.clear()
        Files.deleteIfExists(file.toPath())
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
