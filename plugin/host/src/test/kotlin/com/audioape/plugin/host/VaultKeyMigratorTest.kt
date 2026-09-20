package com.audioape.plugin.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * AA-020c custody migration: FileBackedKeyRing-era records → a target ring (fake OS ring in
 * these JVM tests), with the reviewer-required guarantees:
 *   - retry-safe across file boundaries (A migrated + B failed  =>  retry recovers ALL, lossless)
 *   - crash-safe at every point of the per-file swap (journal + backup repair)
 *   - master key deleted only after every file is DONE
 */
class VaultKeyMigratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun fullSuccessMigratesAllRecordsLosslesslyAndDeletesMasterOnlyAtTheEnd() {
        val dir = tmp.newFolder("vault")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertTrue("migration must succeed: $report", report.succeeded)
        assertEquals(2, report.migratedFiles)
        assertEquals(5, report.rekeyedRecords)
        assertTrue("master key must be deleted after full success", report.masterKeyDeleted)
        assertFalse("legacy master key file must be gone", File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())

        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            val records = readCiphertextFile(File(dir, "vault.$digest.bin")).records
            assertTrue("no migration residue allowed (temp/backup/journal)", residue(dir).isEmpty())
            for (credentialId in records.keys) {
                val plaintext = CipherBlob.decrypt(records[credentialId]!!, target.getOrCreatePluginKey(digest), digest)
                assertEquals(secret(credentialId).toList(), plaintext!!.toList())
            }
        }
    }

    /**
     * THE reviewer scenario: plugin A migrates, plugin B fails -> retry must NOT try to decrypt
     * A's OS-encrypted file with the legacy key; once B is repaired a later retry finishes
     * losslessly and only THEN is the master key deleted.
     */
    @Test
    fun partialFailureThenRetryRecoversEveryFileWithoutLoss() {
        val dir = tmp.newFolder("vault-partial")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)
        tamperRecord(dir, DIGEST_A) // B (processed after A in file order) will fail legacy decrypt

        // Run 1: A migrates + B fails. FAILED so the master key must survive for the retry.
        val first = VaultKeyMigrator(dir, legacy, target).migrateAll()
        assertFalse("first attempt must report the tampered file", first.succeeded)
        assertTrue("tampered file must be listed", first.failedFiles.containsKey("vault.$DIGEST_A.bin"))
        assertFalse("master key must SURVIVE the partial failure", first.masterKeyDeleted)
        assertTrue(File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())

        // The TAMPERED file (A) must be UNTOUCHED: still legacy custody (its intact "refresh"
        // record decrypts under the LEGACY key). The successfully-migrated file (B) must have
        // moved to OS custody (decrypts under TARGET, not legacy).
        val aRecords = readCiphertextFile(File(dir, "vault.$DIGEST_A.bin")).records
        assertNotNull(
            "failed file must stay under legacy custody",
            CipherBlob.decrypt(aRecords["refresh"]!!, legacy.getOrCreatePluginKey(DIGEST_A), DIGEST_A),
        )
        val bRecords = readCiphertextFile(File(dir, "vault.$DIGEST_B.bin")).records
        assertNotNull(
            "migrated file must be OS custody",
            CipherBlob.decrypt(bRecords["tok"]!!, target.getOrCreatePluginKey(DIGEST_B), DIGEST_B),
        )
        assertTrue(
            "no swap residue for the failed file",
            dir.listFiles().orEmpty().none { it.name.contains("${DIGEST_A.substring(0, 4)}") && it.name.contains("bak-migrating") },
        )

        val second = VaultKeyMigrator(dir, legacy, target).migrateAll()
        assertTrue("retry without repairs must fail only on the still-tampered file", !second.succeeded)
        assertFalse("master still required after the failed retry", second.masterKeyDeleted)

        // Repair the tampered file (simulated transient) and retry to completion.
        repairRecord(dir, DIGEST_A, legacy)
        val third = VaultKeyMigrator(dir, legacy, target).migrateAll()
        assertTrue("repaired retry must fully succeed: $third", third.succeeded)
        assertTrue("master must be deleted only when ALL files are done", third.masterKeyDeleted)

        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            val records = readCiphertextFile(File(dir, "vault.$digest.bin")).records
            records.forEach { (id, blob) ->
                val plaintext = CipherBlob.decrypt(blob, target.getOrCreatePluginKey(digest), digest)
                assertEquals(
                    "credential $id of $digest must equal the original",
                    secret(id).toList(),
                    plaintext!!.toList(),
                )
            }
        }
    }

    /** Crash AFTER the swap but BEFORE verification/DONE: recovery must complete it, not lose it. */
    @Test
    fun crashAfterSwapBeforeVerifyIsRecoveredFromBackupAndJournal() {
        val dir = tmp.newFolder("vault-crash-swap")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)

        // Simulate: temp verified + swapped, then crash before final verify/state=DONE.
        val before = readCiphertextFile(File(dir, "vault.$DIGEST_A.bin")).records
        val osKey = target.getOrCreatePluginKey(DIGEST_A)
        val migrated =
            linkedMapOf<String, CipherBlob>().also { m ->
                before.forEach { (id, blob) -> m[id] = CipherBlob.encrypt(osKey, secret(id), DIGEST_A) }
            }
        writeCiphertextFile(File(dir, "vault.$DIGEST_A.bin"), migrated) // final = OS-encrypted now
        Files.move(
            File(dir, "vault.$DIGEST_A.bin").toPath(),
            File(dir, "vault.$DIGEST_A.bin.bak-migrating").toPath(),
        )
        writeCiphertextFile(File(dir, "vault.$DIGEST_A.bin"), migrated) // backup holds the ORIGINAL
        Files.write(
            File(dir, "migration.journal").toPath(),
            "$DIGEST_A=REPLACED_UNVERIFIED".toByteArray(StandardCharsets.UTF_8),
        )

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()
        assertTrue("recovery must complete: $report", report.succeeded)
        // A was migrated in the crashed run, so recovery only VERIFIES it (not a new migration);
        // B migrates normally in this run.
        assertEquals("only B was newly migrated this run", 1, report.migratedFiles)
        assertTrue("master deleted after full recovery", report.masterKeyDeleted)
        assertFalse("backup must be cleaned", File(dir, "vault.$DIGEST_A.bin.bak-migrating").exists())
        // Both files decrypt under the target ring (A recovered, B migrated).
        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            val records = readCiphertextFile(File(dir, "vault.$digest.bin")).records
            records.forEach { (id, blob) ->
                assertNotNull(
                    "$id of $digest must decrypt under the OS key",
                    CipherBlob.decrypt(blob, target.getOrCreatePluginKey(digest), digest),
                )
            }
        }
    }

    /** Crash AFTER backup moved but BEFORE temp->final (final missing): restore + replay. */
    @Test
    fun crashBeforeFinalSwapRestoresOriginalAndReplays() {
        val dir = tmp.newFolder("vault-crash-presolve")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)

        // Original moved aside as backup; final MISSING; journal REPLACED_UNVERIFIED (pre-swap).
        Files.move(
            File(dir, "vault.$DIGEST_A.bin").toPath(),
            File(dir, "vault.$DIGEST_A.bin.bak-migrating").toPath(),
        )
        Files.write(
            File(dir, "migration.journal").toPath(),
            "$DIGEST_A=REPLACED_UNVERIFIED\r\n$DIGEST_B=PENDING".toByteArray(StandardCharsets.UTF_8),
        )

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()
        assertTrue("restore + replay must succeed: $report", report.succeeded)
        assertEquals("both files re-migrate after restore", 2, report.migratedFiles)
        // A restored and re-migrated; B migrated. Both decrypt under target.
        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            val records = readCiphertextFile(File(dir, "vault.$digest.bin")).records
            records.forEach { (id, blob) ->
                val plaintext = CipherBlob.decrypt(blob, target.getOrCreatePluginKey(digest), digest)
                assertEquals(secret(id).toList(), plaintext!!.toList())
            }
        }
    }

    /** Corruption in the swapped file: backup restore wins, then a clean re-migration succeeds. */
    @Test
    fun corruptedSwappedFileIsRestoredFromBackupNotKept() {
        val dir = tmp.newFolder("vault-crash-corrupt")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)

        // Simulate a crash where the swap happened but the new file is CORRUPT:
        // 1) the TRUE original is preserved as the backup (as the migrator does before swapping);
        // 2) a corrupt file sits at the final path.
        Files.move(
            File(dir, "vault.$DIGEST_A.bin").toPath(),
            File(dir, "vault.$DIGEST_A.bin.bak-migrating").toPath(),
        )
        writeCiphertextFile(File(dir, "vault.$DIGEST_A.bin"), linkedMapOf("tok" to CipherBlob(ByteArray(12), ByteArray(4), ByteArray(16))))
        Files.write(
            File(dir, "migration.journal").toPath(),
            "$DIGEST_A=REPLACED_UNVERIFIED".toByteArray(StandardCharsets.UTF_8),
        )

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()
        assertTrue("corrupt-swap recovery must succeed: $report", report.succeeded)
        val records = readCiphertextFile(File(dir, "vault.$DIGEST_A.bin")).records
        assertEquals(
            secret("tok").toList(),
            CipherBlob.decrypt(records["tok"]!!, target.getOrCreatePluginKey(DIGEST_A), DIGEST_A)!!.toList(),
        )
        assertEquals(
            secret("refresh").toList(),
            CipherBlob.decrypt(records["refresh"]!!, target.getOrCreatePluginKey(DIGEST_A), DIGEST_A)!!.toList(),
        )
    }

    /** Keystore failures must NOT look like missing keys: a throwing ring fails the vault. */
    @Test
    fun throwingRingFailurePropagatesOutOfTheVault() {
        val dir = tmp.newFolder("vault-throw")
        val vault = PluginCredentialVault(dir) { ThrowingKeyRing() }
        assertTrue(
            "a keystore failure must surface as VaultIoFailure, not success",
            vault.store("org.audioape.throwing", "tok", "x".toByteArray()) is VaultIoFailure,
        )
        assertTrue(vault.read("org.audioape.throwing", "tok") is VaultIoFailure)
        assertTrue(
            "revocation failure must surface, not report success",
            vault.clearAll("org.audioape.throwing") is VaultIoFailure,
        )
        assertTrue(
            "no credential file may exist after failed store/no-op",
            dir.listFiles().orEmpty().none { it.name.startsWith("vault.") && it.name.endsWith(".bin") },
        )
    }

    /**
     * CRASH MATRIX (reviewer item 4): inject a process-death at EVERY named checkpoint, then run
     * a fresh migrator. Acceptance: the recovery run must converge to exactly one safe state —
     * fully migrated and readable under the OS key (master gone, marker present, no residue) —
     * and must NEVER delete the master while any live backup remains.
     */
    @Test
    fun everyCrashCheckpointConvergesToFullyMigratedState() {
        for (checkpoint in MigrationCheckpoint.entries) {
            val dir = tmp.newFolder("cp-${checkpoint.name}")
            val legacy = FakeVaultKeyRing()
            val target = FakeVaultKeyRing()
            seedVault(dir, legacy)

            val crashing = VaultKeyMigrator(dir, legacy, target)
            crashing.checkpointHook = { point ->
                if (point == checkpoint) throw SimulatedCrash()
            }
            try {
                crashing.migrateAll()
                fail("expected SimulatedCrash at $checkpoint")
            } catch (expected: SimulatedCrash) {
                // crash looks like a crash: nothing cleaned up by the dead process
            }

            val recovery = VaultKeyMigrator(dir, legacy, target)
            val report = recovery.migrateAll()

            assertTrue("[$checkpoint] recovery must succeed: $report", report.succeeded)
            assertFalse("[$checkpoint] legacy master must be gone", File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())
            assertTrue("[$checkpoint] completion marker must exist", File(dir, COMPLETION_MARKER_NAME).exists())
            assertFalse("[$checkpoint] no migration residue", residue(dir).isNotEmpty())
            assertTrue(
                "[$checkpoint] master deleted only with no live backup",
                !report.masterKeyDeleted || dir.listFiles().orEmpty().none { it.name.endsWith(".bak-migrating") },
            )
            for (digest in listOf(DIGEST_A, DIGEST_B)) {
                val records = readCiphertextFile(File(dir, "vault.$digest.bin")).records
                records.forEach { (id, blob) ->
                    val plaintext = CipherBlob.decrypt(blob, target.getOrCreatePluginKey(digest), digest)
                    assertNotNull("[$checkpoint] $id of $digest must decrypt under the OS key", plaintext)
                    assertEquals(
                        "[$checkpoint] $id of $digest must equal the original",
                        secret(id).toList(),
                        plaintext!!.toList(),
                    )
                }
            }
        }
    }

    /** Reviewer item 2: a SECOND migrateAll() after success is a verified no-op. */
    @Test
    fun secondCallAfterSuccessfulMigrationIsAVerifiedNoOp() {
        val dir = tmp.newFolder("vault-idempotent")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)

        val first = VaultKeyMigrator(dir, legacy, target).migrateAll()
        assertTrue("first migration must succeed: $first", first.succeeded)

        val second = VaultKeyMigrator(dir, legacy, target).migrateAll()
        assertTrue("second migration must succeed as a no-op: $second", second.succeeded)
        assertTrue("second run must report alreadyComplete", second.alreadyComplete)
        assertEquals("second run must migrate nothing", 0, second.migratedFiles)
        assertEquals("second run must re-key nothing", 0, second.rekeyedRecords)

        // Nothing recreated, nothing regenerated: no master, no new keys, no residue.
        assertFalse("master must NOT be recreated", File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())
        assertFalse("no new target key may appear", target.hasKey(DIGEST_C))
        assertTrue("credentials must still read under the OS ring", target.hasKey(DIGEST_A) && target.hasKey(DIGEST_B))
        assertFalse("no migration residue after the second run", residue(dir).isNotEmpty())

        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            val records = readCiphertextFile(File(dir, "vault.$digest.bin")).records
            records.forEach { (id, blob) ->
                assertEquals(
                    secret(id).toList(),
                    CipherBlob.decrypt(blob, target.getOrCreatePluginKey(digest), digest)!!.toList(),
                )
            }
        }
    }

    /** Reviewer item 2: completion marker with a stray backup surfaces as explicit failure. */
    @Test
    fun completionMarkerWithStrayBackupIsExplicitFailureNotSilentContinue() {
        val dir = tmp.newFolder("vault-marker-backup")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)
        assertTrue(VaultKeyMigrator(dir, legacy, target).migrateAll().succeeded)

        writeCiphertextFile(
            File(dir, "vault.$DIGEST_A.bin.bak-migrating"),
            linkedMapOf("tok" to CipherBlob(ByteArray(12), ByteArray(4), ByteArray(16))),
        )
        val rerun = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertFalse("marker + stray backup must FAIL, not continue", rerun.succeeded)
        assertTrue("failure must be the inconsistent-state error", rerun.migratorFailure is IllegalStateException)
        assertTrue("stray backup must remain untouched", File(dir, "vault.$DIGEST_A.bin.bak-migrating").exists())
    }

    /** Reviewer item 2: completion marker with a reappeared master surfaces as explicit failure. */
    @Test
    fun completionMarkerWithRecoveredMasterIsExplicitFailure() {
        val dir = tmp.newFolder("vault-marker-master")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)
        assertTrue(VaultKeyMigrator(dir, legacy, target).migrateAll().succeeded)

        Files.write(File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).toPath(), ByteArray(32) { 9 })
        val rerun = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertFalse("marker + reappeared master must FAIL", rerun.succeeded)
        assertTrue(rerun.migratorFailure is IllegalStateException)
    }

    /** Reviewer item 1: crash after markDone() but BEFORE backup deletion -> recovery cleans up. */
    @Test
    fun doneWithLeftoverBackupIsCleanedUpOnRecovery() {
        val dir = tmp.newFolder("vault-done-backup")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedSingle(dir, legacy, DIGEST_A)

        // Crash exactly between journal.markDone() and Files.deleteIfExists(backup).
        val crashing = VaultKeyMigrator(dir, legacy, target)
        var fired = false
        crashing.checkpointHook = { point ->
            if (point == MigrationCheckpoint.BEFORE_BACKUP_DELETE && !fired) {
                fired = true
                throw SimulatedCrash()
            }
        }
        try {
            crashing.migrateAll()
            fail("expected SimulatedCrash at BEFORE_BACKUP_DELETE")
        } catch (expected: SimulatedCrash) {
            // process died; backup still on disk, journal DONE, final OS-encrypted
        }

        val recovery = VaultKeyMigrator(dir, legacy, target)
        val report = recovery.migrateAll()

        assertTrue("DONE+backup recovery must succeed: $report", report.succeeded)
        assertFalse("leftover backup must be removed", File(dir, "vault.$DIGEST_A.bin.bak-migrating").exists())
        assertTrue("master must be deleted after full cleanup", report.masterKeyDeleted)
        assertTrue("completion marker must be created", File(dir, COMPLETION_MARKER_NAME).exists())
        val records = readCiphertextFile(File(dir, "vault.$DIGEST_A.bin")).records
        records.forEach { (id, blob) ->
            assertEquals(
                secret(id).toList(),
                CipherBlob.decrypt(blob, target.getOrCreatePluginKey(DIGEST_A), DIGEST_A)!!.toList(),
            )
        }
    }

    /** Reviewer item 1: DONE + final MISSING + backup + master -> restore and replay, no loss. */
    @Test
    fun doneWithMissingFinalRestoresFromBackupAndReplays() {
        val dir = tmp.newFolder("vault-done-missing")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)
        Files.move(
            File(dir, "vault.$DIGEST_A.bin").toPath(),
            File(dir, "vault.$DIGEST_A.bin.bak-migrating").toPath(),
        )
        Files.write(File(dir, "migration.journal").toPath(), "$DIGEST_A=DONE".toByteArray(StandardCharsets.UTF_8))

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertTrue("DONE+missing-final restore must succeed: $report", report.succeeded)
        val records = readCiphertextFile(File(dir, "vault.$DIGEST_A.bin")).records
        assertEquals(
            secret("tok").toList(),
            CipherBlob.decrypt(records["tok"]!!, target.getOrCreatePluginKey(DIGEST_A), DIGEST_A)!!.toList(),
        )
        assertEquals(
            secret("refresh").toList(),
            CipherBlob.decrypt(records["refresh"]!!, target.getOrCreatePluginKey(DIGEST_A), DIGEST_A)!!.toList(),
        )
    }

    /** Reviewer item 1: DONE + final MISSING + NO backup -> explicit failure, nothing deleted. */
    @Test
    fun doneWithMissingFinalAndNoBackupFailsExplicitlyWithoutDeleting() {
        val dir = tmp.newFolder("vault-done-unrecoverable")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)
        // Source file deleted and NO backup: the DONE claim cannot be validated, nothing recoverable.
        assertTrue(File(dir, "vault.$DIGEST_A.bin").delete())
        Files.write(File(dir, "migration.journal").toPath(), "$DIGEST_A=DONE".toByteArray(StandardCharsets.UTF_8))

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertFalse("unrecoverable DONE must fail explicitly: $report", report.succeeded)
        assertFalse("master must NOT be deleted when recovery is impossible", report.masterKeyDeleted)
        assertTrue("master file must still exist", File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())
        assertFalse("completion marker must NOT be created", File(dir, COMPLETION_MARKER_NAME).exists())
    }

    /** Reviewer item 1: DONE + CORRUPT final + backup + master -> restore, never keep garbage. */
    @Test
    fun doneWithCorruptFinalRestoresFromBackupWhenMasterPresent() {
        val dir = tmp.newFolder("vault-done-corrupt")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)
        Files.move(
            File(dir, "vault.$DIGEST_A.bin").toPath(),
            File(dir, "vault.$DIGEST_A.bin.bak-migrating").toPath(),
        )
        writeCiphertextFile(File(dir, "vault.$DIGEST_A.bin"), linkedMapOf("tok" to CipherBlob(ByteArray(12), ByteArray(4), ByteArray(16))))
        Files.write(File(dir, "migration.journal").toPath(), "$DIGEST_A=DONE".toByteArray(StandardCharsets.UTF_8))

        val report = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertTrue("DONE+corrupt-final restore must succeed: $report", report.succeeded)
        val records = readCiphertextFile(File(dir, "vault.$DIGEST_A.bin")).records
        assertEquals(
            secret("tok").toList(),
            CipherBlob.decrypt(records["tok"]!!, target.getOrCreatePluginKey(DIGEST_A), DIGEST_A)!!.toList(),
        )
        assertEquals(
            secret("refresh").toList(),
            CipherBlob.decrypt(records["refresh"]!!, target.getOrCreatePluginKey(DIGEST_A), DIGEST_A)!!.toList(),
        )
    }

    /**
     * Reviewer item 2 (narrow window): master already deleted but marker not yet created —
     * a rerun must SELF-HEAL by adopting OS-verified files, not fail or re-key anything.
     */
    @Test
    fun masterDeletedWithoutMarkerSelfHealsOnNextRun() {
        val dir = tmp.newFolder("vault-window")
        val legacy = FakeVaultKeyRing()
        val target = FakeVaultKeyRing()
        seedVault(dir, legacy)
        assertTrue(VaultKeyMigrator(dir, legacy, target).migrateAll().succeeded)

        // Simulate: marker lost mid-create (crash window), master already gone, journal discarded.
        assertTrue(File(dir, COMPLETION_MARKER_NAME).delete())
        Files.deleteIfExists(File(dir, "migration.journal").toPath())

        val rerun = VaultKeyMigrator(dir, legacy, target).migrateAll()

        assertTrue("window rerun must self-heal: $rerun", rerun.succeeded)
        assertTrue("marker must be recreated", File(dir, COMPLETION_MARKER_NAME).exists())
        assertFalse("master must stay gone", File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())
        assertFalse("no new target key may be minted", target.hasKey(DIGEST_C))
        for (digest in listOf(DIGEST_A, DIGEST_B)) {
            val records = readCiphertextFile(File(dir, "vault.$digest.bin")).records
            records.forEach { (id, blob) ->
                assertEquals(
                    secret(id).toList(),
                    CipherBlob.decrypt(blob, target.getOrCreatePluginKey(digest), digest)!!.toList(),
                )
            }
        }
    }

    private fun seedVault(
        dir: File,
        legacy: VaultKeyRing,
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
        Files.write(master.toPath(), ByteArray(32) { 7 })
    }

    private fun seedSingle(
        dir: File,
        legacy: VaultKeyRing,
        digest: String,
    ) {
        assertTrue("seed requires an existing vault dir", dir.mkdirs() || dir.exists())
        val records = linkedMapOf<String, CipherBlob>()
        val key = legacy.getOrCreatePluginKey(digest)
        for (credentialId in listOf("tok", "refresh")) {
            records[credentialId] = CipherBlob.encrypt(key, secret(credentialId), digest)
        }
        writeCiphertextFile(File(dir, "vault.$digest.bin"), records)
        val master = File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME)
        Files.write(master.toPath(), ByteArray(32) { 7 })
    }

    private fun tamperRecord(
        dir: File,
        digest: String,
    ) {
        val file = File(dir, "vault.$digest.bin")
        val tampered = readCiphertextFile(file)
        tampered.records["tok"] =
            CipherBlob(
                tampered.records["tok"]!!.iv,
                tampered.records["tok"]!!.ciphertext,
                ByteArray(16) { 0 },
            )
        writeCiphertextFile(file, tampered.records)
    }

    private fun repairRecord(
        dir: File,
        digest: String,
        legacy: VaultKeyRing,
    ) {
        val file = File(dir, "vault.$digest.bin")
        val records = linkedMapOf<String, CipherBlob>()
        records["tok"] = CipherBlob.encrypt(legacy.getOrCreatePluginKey(digest), secret("tok"), digest)
        records["refresh"] = CipherBlob.encrypt(legacy.getOrCreatePluginKey(digest), secret("refresh"), digest)
        writeCiphertextFile(file, records)
    }

    private fun residue(dir: File): List<String> =
        dir
            .listFiles()
            .orEmpty()
            .map { it.name }
            .filter { it.contains("tmp-migrating") || it.contains("bak-migrating") || it.contains("journal") }

    private fun secret(name: String): ByteArray = name.toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}

/** Deterministic in-memory ring that ALWAYS throws — models a real keystore service failure. */
private class ThrowingKeyRing : VaultKeyRing {
    override fun getOrCreatePluginKey(pluginDigest: String) = throw IllegalStateException("keystore service failure (simulated)")

    override fun pluginKeyOrNull(pluginDigest: String) = throw IllegalStateException("keystore service failure (simulated)")

    override fun removePluginKey(pluginDigest: String) = throw IllegalStateException("keystore service failure (simulated)")
}
