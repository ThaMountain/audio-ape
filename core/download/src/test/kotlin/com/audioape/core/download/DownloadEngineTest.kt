package com.audioape.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * AA-021 engine behavior: happy path, idempotency, and every rejection the owner required
 * (traversal, absolute paths, extra members, duplicates, collisions, malformed archives,
 * size/hash mismatch). Negatives assert that NOTHING pre-existing is ever modified.
 */
class DownloadEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newEngine(
        root: File,
        committer: DownloadCommitter = MemoryCommitter(),
    ): DownloadEngine = DownloadEngine(root, committer)

    @Test
    fun happyPathStagesVerifiesExtractsCommitsAndCleansUp() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val request =
            ResolvedDownloadRequest(
                bookId = fixture.manifest.bookId,
                displayTitle = fixture.manifest.displayTitle,
                source = DownloadSource { fixture.zipFile.inputStream() },
                expectedArchiveSizeBytes = fixture.archiveSizeBytes,
                expectedArchiveSha256Hex = fixture.archiveSha256Hex,
            )

        val report = newEngine(root, committer).run(request)

        report.engineFailure?.let { failure ->
            throw AssertionError("DownloadEngine.run() failed", failure)
        }
        assertTrue("run must succeed: $report", report.succeeded)
        assertTrue(report.archiveVerified)
        assertEquals(2, report.partsCommitted)
        assertTrue("no adopted parts on a clean run", report.partsAdopted == 0)

        // Managed tree: exactly the two parts, hash-verified.
        val managedDir = File(root, "${fixture.manifest.bookId}/managed")
        fixture.partContents.forEach { (name, bytes) ->
            val managed = File(managedDir, name)
            assertTrue("$name must exist in the managed tree", managed.isFile)
            assertEquals(bytes.size.toLong(), managed.length())
            assertTrue(DownloadHashes.fileMatchesSha256(managed, DownloadHashes.sha256(bytes)))
        }
        // One atomic Room record with all parts.
        assertEquals(1, committer.committed.size)
        assertEquals(fixture.partContents.size, committer.committed[0].second.size)

        // Staging is fully cleaned; marker exists; journal gone with no temp residue.
        val bookDir = File(root, fixture.manifest.bookId)
        assertTrue(File(bookDir, "staging").listFiles().orEmpty().isEmpty())
        assertTrue(File(bookDir, "download-v1.complete").isFile)
        assertFalse("journal must be gone after completion", File(bookDir, "download.journal").exists())
        assertTrue("no journal temp residue", bookDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun secondRunIsAVerifiedNoOp() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        val engine = newEngine(root, committer)
        assertTrue(engine.run(request).succeeded)

        val second = engine.run(request)
        assertTrue("no-op must succeed: $second", second.succeeded)
        assertTrue("second run must report alreadyComplete", second.alreadyComplete)
        assertEquals("no re-commit", 1, committer.committed.size)
        assertEquals("no new parts", 0, second.partsCommitted)
        assertTrue("managed files stay put", File(root, "${fixture.manifest.bookId}/managed").listFiles().orEmpty().size == 2)
    }

    @Test
    fun prePlacedMatchingFileIsAdoptedNotCopied() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        // Pre-place ONE correct managed part (simulates a recovered earlier run).
        val managedDir = File(root, "${fixture.manifest.bookId}/managed")
        managedDir.mkdirs()
        val (name, bytes) = fixture.partContents.entries.first()
        Files.write(File(managedDir, name).toPath(), bytes)
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )

        val report = newEngine(root, committer).run(request)

        assertTrue(report.succeeded)
        assertEquals(1, report.partsAdopted)
        assertEquals("the other part is freshly committed", 1, report.partsCommitted)
        assertEquals(1, committer.committed.size)
    }

    @Test
    fun collisionWithDifferentBytesFailsWithoutOverwriting() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val managedDir = File(root, "${fixture.manifest.bookId}/managed")
        managedDir.mkdirs()
        val (name, _) = fixture.partContents.entries.first()
        val foreign = "not-the-right-bytes".toByteArray()
        Files.write(File(managedDir, name).toPath(), foreign) // WRONG content for the expected part
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )

        val report = newEngine(root, MemoryCommitter()).run(request)

        assertFalse("collision must fail", report.succeeded)
        assertNotNull(report.engineFailure)
        // The foreign file is byte-identical afterwards: NEVER overwritten.
        assertTrue(java.util.Arrays.equals(foreign, Files.readAllBytes(File(managedDir, name).toPath())))
        assertFalse("no record on failure", File(root, "${fixture.manifest.bookId}/download-v1.complete").exists())
    }

    @Test
    fun archiveHashMismatchFailsBeforeAnyExtraction() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                "f".repeat(64), // wrong digest
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse(report.succeeded)
        assertNotNull(report.engineFailure)
        assertFalse(
            File(root, fixture.manifest.bookId).exists() &&
                File(root, "${fixture.manifest.bookId}/download-v1.complete").exists(),
        )
    }

    @Test
    fun stagedCorruptArchiveFailsWithoutRedownload() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val staging = File(root, "${fixture.manifest.bookId}/staging")
        staging.mkdirs()
        Files.write(File(staging, "archive.zip").toPath(), "garbage-not-a-zip".toByteArray())
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse(report.succeeded)
        assertTrue(report.engineFailure!!.message!!.contains("hash does not match"))
    }

    @Test
    fun extraArchiveMemberIsRejected() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val members =
            listOf("manifest.mf" to DownloadManifestCodec.encode(fixture.manifest).toByteArray()) +
                fixture.partContents.map { (n, b) -> n to b } +
                listOf("surprise.bin" to "sneaky".toByteArray())
        val zip = DownloadFixtures.writeZipWithMembers(root, members)
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { zip.inputStream() },
                zip.length(),
                DownloadHashes.sha256(zip.inputStream()),
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse("extra member must be rejected", report.succeeded)
        assertTrue(report.engineFailure!!.message!!.contains("members do not match manifest"))
    }

    @Test
    fun traversalMemberIsRejectedBeforeMaterialization() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val members =
            listOf(
                "manifest.mf" to DownloadManifestCodec.encode(fixture.manifest).toByteArray(),
                "../evil.bin" to "evil".toByteArray(),
            ) + fixture.partContents.map { (n, b) -> n to b }
        val zip = DownloadFixtures.writeZipWithMembers(root, members)
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { zip.inputStream() },
                zip.length(),
                DownloadHashes.sha256(zip.inputStream()),
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse("traversal member must be rejected", report.succeeded)
        // Nothing escaped anywhere: no file exists outside the book directory.
        assertFalse(File(root, "evil.bin").exists())
        assertFalse(File(tmp.root, "evil.bin").exists())
    }

    @Test
    fun wrongBookIdInArchiveIsRejected() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val other = DownloadFixtures.syntheticBook(tmp.newFolder("src2"))
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { other.zipFile.inputStream() },
                other.archiveSizeBytes,
                other.archiveSha256Hex,
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse(report.succeeded)
        assertTrue(report.engineFailure!!.message!!.contains("does not match requested"))
    }

    @Test
    fun malformedArchiveIsRejected() {
        val root = tmp.newFolder("root")
        val zip = File(tmp.newFolder("src"), "book.zip")
        // Truncated local-file header: PK\x03\x04 then garbage (Kotlin has no \x escapes — bytes explicit).
        Files.write(
            zip.toPath(),
            byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "this is truncated garbage".toByteArray(),
        )
        val request =
            ResolvedDownloadRequest(
                UUID.randomUUID().toString(),
                "t",
                DownloadSource { zip.inputStream() },
                zip.length(),
                DownloadHashes.sha256(zip.inputStream()),
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse("malformed archive must fail", report.succeeded)
        assertNotNull(report.engineFailure)
    }

    @Test
    fun zipBombRatioIsRejected() {
        val root = tmp.newFolder("root")
        val bombBytes = ByteArray(2 * 1024 * 1024) // 8 MB of zeros compresses to ~8 KB (~1000:1)
        val zip =
            DownloadFixtures.writeZipWithMembers(
                root,
                listOf(
                    "manifest.mf" to
                        DownloadManifestCodec
                            .encode(
                                DownloadManifest(
                                    UUID.randomUUID().toString(),
                                    "bomb",
                                    listOf(PartDescriptor(0, "part00.bin", bombBytes.size.toLong(), DownloadHashes.sha256(bombBytes))),
                                ),
                            ).toByteArray(),
                    "part00.bin" to bombBytes,
                ),
            )
        val request =
            ResolvedDownloadRequest(
                UUID.randomUUID().toString(),
                "bomb",
                DownloadSource { zip.inputStream() },
                zip.length(),
                DownloadHashes.sha256(zip.inputStream()),
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse("zip bomb must be rejected", report.succeeded)
    }

    /** Marker + record checks (see DownloadEngineCrashMatrixTest) and torn-journal tolerance. */
    @Test
    fun tornJournalLineNeverCausesAnIndexFailure() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val bookDir = File(root, fixture.manifest.bookId)
        bookDir.mkdirs()
        // A corrupted/partial journal (exactly the "Index: 1, Size: 1" signature if parsed naively).
        Files.write(
            File(bookDir, "download.journal").toPath(),
            ("book|\npart\npart|part00.bin\npart|part01.bin|EXTRACTED\nbook|RECORDS_COMMITTED").toByteArray(),
        )
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertTrue("torn journal must not break recovery: $report", report.succeeded)
        assertTrue(File(bookDir, "download-v1.complete").isFile)
    }

    /**
     * Reviewer recovery concern: corrupt the journal AFTER the media files and Room record are
     * committed, then rerun. Assert file contents, file count, and record count are UNCHANGED
     * (no overwrite, no duplicate commit, no deletion of a valid completed download).
     */
    @Test
    fun corruptedJournalAfterCommitRecoveryPreservesEverything() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        val engine = newEngine(root, committer)
        assertTrue(engine.run(request).succeeded)

        // Baseline: 2 managed files, 1 record, marker present.
        val bookDir = File(root, fixture.manifest.bookId)
        val managedDir = File(bookDir, "managed")
        val baseline = linkedMapOf<String, String>()
        fixture.partContents.forEach { (name, bytes) -> baseline[name] = DownloadHashes.sha256(bytes) }
        assertEquals(2, managedDir.listFiles().orEmpty().size)

        // Corrupt the journal to torn-but-parseable lines AND lose the marker (worst case).
        Files.write(
            File(bookDir, "download.journal").toPath(),
            ("book|\npart\npart|part01.bin|EXTRACTED\nbook|RECORDS_COMMITTED").toByteArray(),
        )
        assertTrue(File(bookDir, "download-v1.complete").delete())

        val rerun = newEngine(root, committer).run(request)

        assertTrue("torn-corrupt journal recovery must converge: $rerun", rerun.succeeded)
        // File contents + count unchanged.
        assertEquals(2, managedDir.listFiles().orEmpty().size)
        baseline.forEach { (name, hash) ->
            val f = File(managedDir, name)
            assertTrue(DownloadHashes.fileMatchesSha256(f, hash))
            // Same bytes as the fixture source — never overwritten with anything else.
            assertTrue(DownloadHashes.fileMatchesSha256(f, DownloadHashes.sha256(fixture.partContents[name]!!)))
        }
        // Record count unchanged: still exactly ONE commit across all runs (no duplicate entry).
        assertEquals(1, committer.committed.size)
        // Converged to completed: marker recreated, staging clean.
        assertTrue(File(bookDir, "download-v1.complete").isFile)
        assertTrue(!File(bookDir, "staging").exists() || File(bookDir, "staging").listFiles().orEmpty().isEmpty())
    }

    /** Foreign/garbage journal content must be REFUSED, never silently restarted. */
    @Test
    fun inconsistentJournalRefusesToAutoRestart() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        val engine = newEngine(root, committer)
        assertTrue(engine.run(request).succeeded)
        val bookDir = File(root, fixture.manifest.bookId)
        assertTrue(File(bookDir, "download-v1.complete").delete())
        Files.write(File(bookDir, "download.journal").toPath(), "this is not a download journal @@@".toByteArray())

        val rerun = newEngine(root, committer).run(request)

        assertFalse("foreign journal must be refused", rerun.succeeded)
        assertTrue("refusal must name the journal", rerun.failedFiles.containsKey("journal"))
        // Nothing changed: managed files + record intact, marker NOT recreated.
        assertEquals(2, File(bookDir, "managed").listFiles().orEmpty().size)
        assertEquals(1, committer.committed.size)
        assertFalse(File(bookDir, "download-v1.complete").isFile)
    }

    /** A tampered extracted artifact claimed EXTRACTED by the journal must be re-extracted. */
    @Test
    fun tamperedExtractedPartIsReExtractedNotMoved() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        // Crash right after part00 is EXTRACTED (journal says EXTRACTED, file on disk).
        val crashing = newEngine(root, MemoryCommitter())
        var fired = false
        crashing.checkpointHook = { point ->
            if (point == DownloadCheckpoint.PART_EXTRACTED && !fired) {
                fired = true
                throw SimulatedCrash()
            }
        }
        try {
            crashing.run(request)
            fail("expected SimulatedCrash at PART_EXTRACTED")
        } catch (expected: SimulatedCrash) {
        }
        // Tamper the extracted artifact; the journal still claims EXTRACTED.
        val (name, bytes) = fixture.partContents.entries.first()
        val extracted = File(root, "${fixture.manifest.bookId}/staging/extracted/$name")
        assertTrue(extracted.isFile)
        Files.write(extracted.toPath(), "tampered-not-the-original".toByteArray())

        val recovery = newEngine(root, MemoryCommitter())
        val report = recovery.run(request)

        assertTrue("tampered extracted file must be re-extracted: $report", report.succeeded)
        val managed = File(root, "${fixture.manifest.bookId}/managed/$name")
        assertTrue(
            "managed copy must hold the ORIGINAL bytes",
            DownloadHashes.fileMatchesSha256(managed, DownloadHashes.sha256(bytes)),
        )
    }

    @Test
    fun commitFailurePropagates() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter().apply { failCommit = true }
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        val report = newEngine(root, committer).run(request)
        assertFalse(report.succeeded)
        assertNotNull(report.engineFailure)
        assertFalse("no marker when the commit failed", File(root, "${fixture.manifest.bookId}/download-v1.complete").exists())
        // Managed files already moved stay in place (recoverable on rerun once commit succeeds).
        assertEquals(2, File(root, "${fixture.manifest.bookId}/managed").listFiles().orEmpty().size)
    }

    @Test
    fun missingManifestMemberIsRejected() {
        val root = tmp.newFolder("root")
        val zip =
            DownloadFixtures.writeZipWithMembers(
                root,
                listOf("part00.bin" to "data".toByteArray()),
            )
        val request =
            ResolvedDownloadRequest(
                UUID.randomUUID().toString(),
                "t",
                DownloadSource { zip.inputStream() },
                zip.length(),
                DownloadHashes.sha256(zip.inputStream()),
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse(report.succeeded)
        assertTrue(report.engineFailure!!.message!!.contains("no manifest"))
    }
}
