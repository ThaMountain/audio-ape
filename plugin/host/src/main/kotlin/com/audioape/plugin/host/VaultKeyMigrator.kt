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
 * STATE MACHINE (per plugin file) — invariants enforced by code + tests:
 *
 *   PENDING                  final file is still LEGACY custody; the legacy master key must
 *                            remain available while any file is PENDING.
 *   REPLACED_UNVERIFIED      the original is preserved as `*.bak-migrating` until the target
 *                            final is verified; recovery PREFERS preserving credentials over
 *                            cleanup (restore-before-replay).
 *   DONE                     final file is VERIFIED under the OS key; no backup remains; no
 *                            temp remains. DONE is a RECOVERY state on entry, never an
 *                            unconditional early return: re-verify, clean residue, then return.
 *
 *   GLOBAL COMPLETE          every digest is DONE; all finals verify under OS custody; no
 *                            backups; no temp files; legacy master DELETED; completion marker
 *                            committed LAST.
 *
 * DURABILITY ORDER: states are recorded in a write-ahead journal (atomic rewrite) BEFORE the
 * file operations they guard; when in doubt a crash leaves REDUNDANT recoverable data (backup +
 * journal) rather than deleting early.
 *
 * IDEMPOTENCE: a versioned completion marker (`vault-keystore-migration-v1.complete`) is created
 * only after the entire protocol above completes. Entry with the marker present returns a
 * verified no-op; marker present with stray backups/temp/master is an explicit inconsistent-state
 * failure, never a silent continue. A crash in the narrow window between master deletion and
 * marker creation self-heals: files that fail legacy decryption but VERIFY under an existing OS
 * key are adopted as DONE (no re-keying, nothing destructive).
 */
class VaultKeyMigrator(
    private val vaultDirectory: File,
    private val legacyRing: VaultKeyRing = FileBackedKeyRing(vaultDirectory),
    private val targetRing: VaultKeyRing = AndroidKeyRing(),
) {
    private val journal = MigrationJournal(File(vaultDirectory, JOURNAL_FILE_NAME))
    private val originalsByDigest = linkedMapOf<String, MutableMap<String, ByteArray>>()

    /**
     * TEST SEAM: deterministic crash injection. When set, the hook runs at every named
     * [MigrationCheckpoint]; tests make it throw [SimulatedCrash] to model process death at that
     * exact point. Recovery is then exercised by instantiating a FRESH migrator (no hook).
     */
    internal var checkpointHook: ((MigrationCheckpoint) -> Unit)? = null

    private fun at(checkpoint: MigrationCheckpoint) {
        checkpointHook?.invoke(checkpoint)
    }

    fun migrateAll(): MigrationReport {
        val report = MigrationReport()
        val allFiles = vaultDirectory.listFiles() ?: emptyArray()
        if (completionMarker.exists()) {
            // Idempotent no-op entry — but only when the world really is complete.
            val master = masterFile()
            val liveBackup = allFiles.any { it.name.endsWith(BACKUP_SUFFIX) }
            val liveTemp = allFiles.any { it.name.endsWith(MIGRATING_TEMP_SUFFIX) }
            if (master.exists() || liveBackup || liveTemp) {
                report.migratorFailure =
                    IllegalStateException(
                        "migration completion marker present but state is inconsistent " +
                            "(master=${master.exists()}, backup=$liveBackup, temp=$liveTemp) — refusing to continue",
                    )
            } else {
                // Marker proves completion; any lingering journal is inert bookkeeping — discard it.
                journal.discard()
                report.alreadyComplete = true
            }
            return report
        }
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
            // Stray temps are orphaned writes (originals untouched) — safe to clear before the gate.
            cleanupTemps(vaultDirectory.listFiles() ?: emptyArray())
            // The gate MUST use a FRESH listing: recovery within this very run may have deleted
            // backups that the entry-time snapshot still listed (stale-snapshot bug caught by tests).
            val liveBackup = vaultDirectory.listFiles().orEmpty().any { it.name.endsWith(BACKUP_SUFFIX) }
            // DONE digests with NEITHER final NOR backup are unverifiable completion claims
            // (their file vanished after completion) — surface as an explicit failure, never
            // let the gate treat them as finished.
            val invalidDone =
                journal
                    .doneDigests()
                    .filter { digest ->
                        val final = File(vaultDirectory, "$PLUGIN_FILE_PREFIX$digest$PLUGIN_FILE_SUFFIX")
                        val backup = File(vaultDirectory, "$PLUGIN_FILE_PREFIX$digest$PLUGIN_FILE_SUFFIX$BACKUP_SUFFIX")
                        !final.exists() && !backup.exists()
                    }
            if (invalidDone.isNotEmpty()) {
                report.failedFiles["DONE-unverifiable"] =
                    IllegalStateException(
                        "journal claims DONE but final AND backup are missing for: $invalidDone",
                    )
            }
            if (report.failedFiles.isEmpty() && unfinished.isEmpty() && !liveBackup) {
                at(MigrationCheckpoint.BEFORE_MASTER_DELETE)
                if (Files.deleteIfExists(masterFile().toPath())) {
                    report.masterKeyDeleted = true
                }
                at(MigrationCheckpoint.AFTER_MASTER_DELETE)
                // Journal discard precedes the marker so the marker is the LAST durable artifact;
                // a crash after the marker can never leave journal residue.
                journal.discard()
                at(MigrationCheckpoint.BEFORE_MARKER_CREATE)
                Files.write(completionMarker.toPath(), COMPLETION_MARKER_CONTENT.toByteArray(StandardCharsets.UTF_8))
                at(MigrationCheckpoint.AFTER_MARKER_CREATE)
            }
        } catch (simulatedCrash: SimulatedCrash) {
            throw simulatedCrash // a crash must look like a crash: NO cleanup is attempted
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
        val temp = File(vaultDirectory, file.name + MIGRATING_TEMP_SUFFIX)
        try {
            when (journal.stateOf(digest)) {
                // DONE is a RECOVERY state, not an unconditional return: the invariant
                // "DONE = final verified under OS key AND no legacy backup remains" must be
                // re-established even when a crash hit between markDone() and backup deletion.
                JournalState.DONE -> {
                    if (verifyUnderTarget(file, digest)) {
                        Files.deleteIfExists(backup.toPath())
                        Files.deleteIfExists(temp.toPath())
                        return
                    }
                    // Invalid DONE (final missing / undecryptable). Restore the legacy copy ONLY
                    // when one provably exists (backup + master); otherwise FAIL EXPLICITLY and
                    // delete nothing.
                    if (backup.exists() && masterFile().exists()) {
                        restoreBackup(file, backup, digest) // -> PENDING, falls through to replay
                    } else {
                        throw IllegalStateException(
                            "DONE digest ${file.name} is not verifiable under the OS key and no " +
                                "recoverable legacy copy exists",
                        )
                    }
                }

                JournalState.REPLACED_UNVERIFIED -> {
                    if (backup.exists()) {
                        if (verifyUnderTarget(file, digest)) {
                            journal.markDone(digest)
                            Files.deleteIfExists(backup.toPath())
                        } else {
                            // Prefer preserving the legacy original over cleanup.
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
            try {
                records.forEach { (credentialId, blob) ->
                    val plaintext = CipherBlob.decrypt(blob, legacyKey, digest)
                    require(plaintext != null) { "record $credentialId in ${file.name} failed legacy decryption" }
                    originals[credentialId] = plaintext
                }
            } catch (notLegacy: Throwable) {
                // Not legacy custody. Covers the crash window after master deletion but before
                // marker creation: already-OS files are ADOPTED as DONE (verified, not re-keyed).
                if (verifyUnderTarget(file, digest)) {
                    Files.deleteIfExists(backup.toPath())
                    Files.deleteIfExists(temp.toPath())
                    journal.markDone(digest)
                    return
                }
                throw notLegacy
            }
            originalsByDigest[digest] = originals

            // Build + verify the TEMP file BEFORE touching the real one.
            val migrated = linkedMapOf<String, CipherBlob>()
            originals.forEach { (credentialId, plaintext) ->
                migrated[credentialId] = CipherBlob.encrypt(targetKey, plaintext, digest)
            }
            writeCiphertextFile(temp, migrated)
            at(MigrationCheckpoint.TEMP_CREATED)
            verifyRecords(temp, targetKey, digest, originals) // throws on any mismatch
            at(MigrationCheckpoint.TEMP_VERIFIED)

            // Swap with write-ahead journal state + backup of the original.
            journal.markReplacedUnverified(digest)
            at(MigrationCheckpoint.JOURNAL_REPLACED_UNVERIFIED)
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            at(MigrationCheckpoint.ORIGINAL_MOVED_TO_BACKUP)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            at(MigrationCheckpoint.TEMP_MOVED_TO_FINAL)

            verifyRecords(file, targetKey, digest, originals) // post-swap; throws -> restore below
            at(MigrationCheckpoint.FINAL_VERIFIED)
            journal.markDone(digest)
            at(MigrationCheckpoint.JOURNAL_DONE)
            at(MigrationCheckpoint.BEFORE_BACKUP_DELETE)
            Files.deleteIfExists(backup.toPath())
            at(MigrationCheckpoint.AFTER_BACKUP_DELETE)

            report.migratedFiles += 1
            report.rekeyedRecords += originals.size
        } catch (simulatedCrash: SimulatedCrash) {
            throw simulatedCrash // a crash must look like a crash: no per-file cleanup either
        } catch (fileFailure: Throwable) {
            if (backup.exists() && journal.stateOf(digest) != JournalState.DONE) {
                restoreBackup(file, backup, digest)
            }
            Files.deleteIfExists(temp.toPath())
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
     * Verify-only check that every record decrypts under an EXISTING target key (never creates
     * one — a vanished alias means "not migrated", not "mint a key"). True plaintext-equality was
     * enforced when the TEMP was verified pre-swap; recovery can only re-verify decryptability.
     */
    private fun verifyUnderTarget(
        file: File,
        digest: String,
    ): Boolean =
        try {
            val key = targetRing.pluginKeyOrNull(digest)
            val records = readCiphertextFile(file).records
            key != null && records.isNotEmpty() && records.values.all { CipherBlob.decrypt(it, key, digest) != null }
        } catch (_: Throwable) {
            false
        }

    private fun cleanupTemps(pluginFiles: Array<File>) {
        pluginFiles
            .filter { it.name.endsWith(MIGRATING_TEMP_SUFFIX) }
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun masterFile(): File = File(vaultDirectory, FileBackedKeyRing.MASTER_KEY_FILE_NAME)

    private val completionMarker: File = File(vaultDirectory, COMPLETION_MARKER_NAME)

    private companion object {
        const val JOURNAL_FILE_NAME = "migration.journal"
        const val COMPLETION_MARKER_CONTENT = "audio-ape keystore custody migration v1 complete"
    }
}

/** Versioned completion marker file name (reviewer item 2); created LAST after full success. */
internal const val COMPLETION_MARKER_NAME = "vault-keystore-migration-v1.complete"

/** Named interruption points for the deterministic crash-matrix tests ([SimulatedCrash]). */
internal enum class MigrationCheckpoint {
    TEMP_CREATED,
    TEMP_VERIFIED,
    JOURNAL_REPLACED_UNVERIFIED,
    ORIGINAL_MOVED_TO_BACKUP,
    TEMP_MOVED_TO_FINAL,
    FINAL_VERIFIED,
    JOURNAL_DONE,
    BEFORE_BACKUP_DELETE,
    AFTER_BACKUP_DELETE,
    BEFORE_MASTER_DELETE,
    AFTER_MASTER_DELETE,
    BEFORE_MARKER_CREATE,
    AFTER_MARKER_CREATE,
}

/** Thrown by the [MigrationCheckpoint] test seam to model process death. */
internal class SimulatedCrash : RuntimeException("simulated process crash")

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

    /** Digests whose journal state claims completion (validated separately at the finish gate). */
    fun doneDigests(): List<String> = states.filterValues { it == JournalState.DONE }.keys.toList()

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
    var alreadyComplete: Boolean = false,
    var failedFiles: MutableMap<String, Throwable> = linkedMapOf(),
    var migratorFailure: Throwable? = null,
) {
    val succeeded: Boolean
        get() = failedFiles.isEmpty() && migratorFailure == null
}
