package com.audioape.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AA-021 engine behavior: happy path, idempotency, every rejection the owner required, and the
 * security/correctness hardening regressions (findings 1-6): false-completion blocking,
 * no-overwrite publication + race, identity-aware Room commit, bounded initial scan, book-id
 * validation-before-filesystem, journal durability artifacts.
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
        val request = DownloadFixtures.fixtureRequest(fixture)
        val report = newEngine(root, committer).run(request)

        report.engineFailure?.let { failure ->
            throw AssertionError("DownloadEngine.run() failed", failure)
        }
        assertTrue("run must succeed: $report", report.succeeded)
        assertTrue("archiveVerified: $report", report.archiveVerified)
        assertEquals("partsCommitted: $report", 2, report.partsCommitted)
        assertEquals(0, report.partsAdopted)

        val managedDir = File(root, "${fixture.manifest.bookId}/managed")
        fixture.partContents.forEach { (name, bytes) ->
            val managed = File(managedDir, name)
            assertTrue("$name must exist in the managed tree", managed.isFile)
            assertEquals(bytes.size.toLong(), managed.length())
            assertTrue(DownloadHashes.fileMatchesSha256(managed, DownloadHashes.sha256(bytes)))
        }
        assertEquals(1, committer.committed.size)
        assertEquals(fixture.partContents.size, committer.committed[0].parts.size)

        val bookDir = File(root, fixture.manifest.bookId)
        assertTrue(
            "staging must be empty after completion, found: " +
                File(bookDir, "staging").listFiles().orEmpty().map { it.name },
            File(bookDir, "staging").listFiles().orEmpty().isEmpty(),
        )
        assertTrue(File(bookDir, "download-v1.complete").isFile)
        assertFalse("journal must be gone after completion", File(bookDir, "download.journal").exists())
        assertTrue("no journal temp residue", bookDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun secondRunIsAVerifiedNoOp() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val request = DownloadFixtures.fixtureRequest(fixture)
        val engine = newEngine(root, committer)
        assertTrue(engine.run(request).succeeded)

        val second = engine.run(request)
        assertTrue(second.succeeded)
        assertTrue(second.alreadyComplete)
        assertEquals(1, committer.committed.size)
        assertEquals(0, second.partsCommitted)
        assertTrue(File(root, "${fixture.manifest.bookId}/managed").listFiles().orEmpty().size == 2)
    }

    @Test
    fun prePlacedMatchingFileIsAdoptedNotCopied() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val managedDir = File(root, "${fixture.manifest.bookId}/managed")
        managedDir.mkdirs()
        val (name, bytes) = fixture.partContents.entries.first()
        Files.write(File(managedDir, name).toPath(), bytes)
        val request = DownloadFixtures.fixtureRequest(fixture)

        val report = newEngine(root, committer).run(request)

        assertTrue(report.succeeded)
        assertEquals(1, report.partsAdopted)
        assertEquals(1, report.partsCommitted)
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
        Files.write(File(managedDir, name).toPath(), foreign)
        val request = DownloadFixtures.fixtureRequest(fixture)

        val report = newEngine(root, MemoryCommitter()).run(request)

        assertFalse("collision must fail", report.succeeded)
        assertNotNull(report.engineFailure)
        assertTrue(java.util.Arrays.equals(foreign, Files.readAllBytes(File(managedDir, name).toPath())))
        assertFalse(File(root, "${fixture.manifest.bookId}/download-v1.complete").exists())
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
                "f".repeat(64),
                fixture.manifest.parts,
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
        val request = DownloadFixtures.fixtureRequest(fixture)
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
            DownloadFixtures.fixtureRequest(fixture).let {
                it.copy(
                    source =
                        DownloadSource {
                            zip.inputStream()
                        },
                    expectedArchiveSizeBytes = zip.length(),
                    expectedArchiveSha256Hex = DownloadHashes.sha256(zip.inputStream()),
                )
            }
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
            DownloadFixtures.fixtureRequest(fixture).let {
                it.copy(
                    source =
                        DownloadSource {
                            zip.inputStream()
                        },
                    expectedArchiveSizeBytes = zip.length(),
                    expectedArchiveSha256Hex = DownloadHashes.sha256(zip.inputStream()),
                )
            }
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse("traversal member must be rejected", report.succeeded)
        assertFalse(File(root, "evil.bin").exists())
        assertFalse(File(tmp.root, "evil.bin").exists())
    }

    @Test
    fun wrongBookIdInArchiveIsRejected() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val other = DownloadFixtures.syntheticBook(tmp.newFolder("src2"))
        val request =
            DownloadFixtures.fixtureRequest(fixture).let {
                it.copy(
                    bookId = fixture.manifest.bookId,
                    source = DownloadSource { other.zipFile.inputStream() },
                    expectedArchiveSizeBytes = other.archiveSizeBytes,
                    expectedArchiveSha256Hex = other.archiveSha256Hex,
                    expectedParts = other.manifest.parts,
                )
            }
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse(report.succeeded)
        assertTrue(report.engineFailure!!.message!!.contains("does not match requested"))
    }

    @Test
    fun malformedArchiveIsRejected() {
        val root = tmp.newFolder("root")
        val zip = File(tmp.newFolder("src"), "book.zip")
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
                listOf(PartDescriptor(0, "a.bin", 1L, "0".repeat(64))),
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse("malformed archive must fail", report.succeeded)
        assertNotNull(report.engineFailure)
    }

    @Test
    fun zipBombRatioIsRejected() {
        val root = tmp.newFolder("root")
        val bombBytes = ByteArray(2 * 1024 * 1024)
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
                listOf(PartDescriptor(0, "part00.bin", bombBytes.size.toLong(), DownloadHashes.sha256(bombBytes))),
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse("zip bomb must be rejected", report.succeeded)
    }

    // ---------- FINDING 1: no false completion without proof -------------------------------------

    private fun runToCompletion(
        root: File,
        fixture: DownloadFixtures.SyntheticZip,
        committer: MemoryCommitter,
    ) {
        assertTrue(newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture)).succeeded)
    }

    @Test
    fun archivedDeletedJournalWithoutRoomRecordFailsExplicitly() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        runToCompletion(root, fixture, committer)
        val bookDir = File(root, fixture.manifest.bookId)
        assertTrue(File(bookDir, "download-v1.complete").delete())
        committer.committed.clear() // the Room record is GONE
        Files.write(File(bookDir, "download.journal").toPath(), "book|ARCHIVE_DELETED".toByteArray())

        val rerun = newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture))

        assertFalse("ARCHIVE_DELETED without a record must NOT complete", rerun.succeeded)
        assertFalse("no false success marker", File(bookDir, "download-v1.complete").isFile)
        assertTrue("media must remain untouched", File(bookDir, "managed").listFiles().orEmpty().size == 2)
    }

    @Test
    fun recordsCommittedJournalMissingManagedFileFailsWithoutMarker() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        runToCompletion(root, fixture, committer)
        val bookDir = File(root, fixture.manifest.bookId)
        assertTrue(File(bookDir, "download-v1.complete").delete())
        // Delete ONE managed file; journal claims RECORDS_COMMITTED with no part lines at all.
        assertTrue(File(bookDir, "managed/part00.bin").delete())
        Files.write(
            File(bookDir, "download.journal").toPath(),
            "book|RECORDS_COMMITTED".toByteArray(),
        )

        val rerun = newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture))

        // Archive is gone + content unverifiable -> explicit failure, no marker, no re-commit.
        assertFalse("unverifiable RECORDS_COMMITTED must fail", rerun.succeeded)
        assertFalse(File(bookDir, "download-v1.complete").isFile)
        assertEquals("no duplicate record", 1, committer.committed.size)
    }

    @Test
    fun completionMarkerWithMismatchedMediaFailsAndPreservesBytes() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        runToCompletion(root, fixture, committer)
        val bookDir = File(root, fixture.manifest.bookId)
        // Tamper a managed file while the marker is still present.
        val target = File(bookDir, "managed/part00.bin")
        val tampered = "tampered-bytes".toByteArray()
        Files.write(target.toPath(), tampered)

        val rerun = newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture))

        assertFalse("marker + mismatched media must fail, not no-op", rerun.succeeded)
        assertTrue(
            "tampered bytes must remain untouched",
            java.util.Arrays.equals(tampered, Files.readAllBytes(target.toPath())),
        )
        assertEquals(1, committer.committed.size)
    }

    @Test
    fun emptyJournalCanNeverEstablishCompletion() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        runToCompletion(root, fixture, committer)
        val bookDir = File(root, fixture.manifest.bookId)
        assertTrue(File(bookDir, "download-v1.complete").delete())
        // Corrupt one managed file; empty journal + archive gone.
        val target = File(bookDir, "managed/part00.bin")
        val tampered = "tampered".toByteArray()
        Files.write(target.toPath(), tampered)
        Files.write(File(bookDir, "download.journal").toPath(), ByteArray(0))

        val rerun = newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture))

        // Empty journal must NOT establish completion: with the archive gone and media
        // mismatched, the only safe outcome is an explicit failure (never overwrite).
        assertFalse("empty journal must not imply completion", rerun.succeeded)
        assertTrue(
            "tampered bytes must stay untouched",
            java.util.Arrays.equals(tampered, Files.readAllBytes(target.toPath())),
        )
        assertFalse(File(bookDir, "download-v1.complete").isFile)
        assertEquals("no duplicate record", 1, committer.committed.size)
    }

    // ---------- FINDING 2: no-overwrite publication + races --------------------------------------

    @Test
    fun competingDestinationAppearingBeforePublicationIsNeverOverwritten() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val engine = newEngine(root, MemoryCommitter())
        val foreign = "foreign-bytes-that-appear-mid-race".toByteArray()
        var injected = false
        engine.checkpointHook = { point ->
            // Side-effect hook: inject the competing destination at the publication gate, then
            // let publication proceed (this is a RACE, not a crash).
            if (point == DownloadCheckpoint.PART_PUBLISHING && !injected) {
                injected = true
                val managedDir = File(root, "${fixture.manifest.bookId}/managed")
                managedDir.mkdirs()
                Files.write(File(managedDir, fixture.partContents.keys.first()).toPath(), foreign)
            }
        }

        val report = engine.run(DownloadFixtures.fixtureRequest(fixture))

        assertTrue("race injection must have fired", injected)
        assertFalse("competing destination must cause a collision failure", report.succeeded)
        val dest = File(root, "${fixture.manifest.bookId}/managed/${fixture.partContents.keys.first()}")
        assertTrue(
            "competing bytes must remain byte-identical",
            java.util.Arrays.equals(foreign, Files.readAllBytes(dest.toPath())),
        )
        assertFalse(File(root, "${fixture.manifest.bookId}/download-v1.complete").exists())
    }

    @Test
    fun concurrentSameBookDownloadsPublishExactlyOneRecord() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val request = DownloadFixtures.fixtureRequest(fixture)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = java.util.Collections.synchronizedList(mutableListOf<DownloadReport>())
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) {
            pool.execute {
                start.await()
                results += newEngine(root, committer).run(request)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(2, results.size)
        assertTrue("at least one run must succeed (other may adopt/collide safely)", results.any { it.succeeded })
        assertEquals("exactly ONE library record across both concurrent runs", 1, committer.committed.size)
        val managedDir = File(root, "${fixture.manifest.bookId}/managed")
        fixture.partContents.forEach { (name, bytes) ->
            assertTrue(DownloadHashes.fileMatchesSha256(File(managedDir, name), DownloadHashes.sha256(bytes)))
        }
    }

    // ---------- FINDING 4: bounded initial scan ---------------------------------------------------

    @Test
    fun oversizedCompressibleEntryIsRejectedDuringScanWithNoExtraction() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        // A legit manifest + one EXTRA member that is highly compressible but huge when expanded.
        val bomb = ByteArray(4 * 1024 * 1024) // zeros -> ~4 KiB compressed (~1000:1 inflate ratio)
        val members =
            listOf("manifest.mf" to DownloadManifestCodec.encode(fixture.manifest).toByteArray()) +
                fixture.partContents.map { (n, b) -> n to b } +
                listOf("big_extra.bin" to bomb)
        val zip = DownloadFixtures.writeZipWithMembers(root, members)
        val request =
            DownloadFixtures.fixtureRequest(fixture).let {
                it.copy(
                    source =
                        DownloadSource {
                            zip.inputStream()
                        },
                    expectedArchiveSizeBytes = zip.length(),
                    expectedArchiveSha256Hex = DownloadHashes.sha256(zip.inputStream()),
                )
            }
        val report = newEngine(root, MemoryCommitter()).run(request)

        assertFalse("oversized compressible member must be rejected during the scan", report.succeeded)
        assertTrue(
            "no managed files may be produced",
            !File(root, fixture.manifest.bookId).exists() ||
                File(root, "${fixture.manifest.bookId}/managed").listFiles().orEmpty().isEmpty(),
        )
        assertFalse(File(root, fixture.manifest.bookId).exists() && File(root, "${fixture.manifest.bookId}/download-v1.complete").isFile)
    }

    @Test
    fun requestRejectsExpectedSizeAboveConfiguredArchiveCap() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        assertThrows(IllegalArgumentException::class.java) {
            val request =
                DownloadFixtures.fixtureRequest(fixture).let {
                    it.copy(maxArchiveSizeBytes = 1024L, expectedArchiveSizeBytes = 2048L)
                }
            newEngine(root, MemoryCommitter()).run(request)
        }
    }

    // ---------- FINDING 5: book-id validated before ANY filesystem access ------------------------

    @Test
    fun invalidBookIdThrowsBeforeAnyFilesystemMutation() {
        val root = tmp.newFolder("root")
        for (bad in listOf("not-a-uuid", "../evil", "a/b", "", "..")) {
            val before =
                root
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .toSet()
            try {
                DownloadEngine(root, MemoryCommitter()).run(
                    ResolvedDownloadRequest(
                        bad,
                        "t",
                        DownloadSource { java.io.ByteArrayInputStream(ByteArray(0)) },
                        0L,
                        "0".repeat(64),
                        listOf(PartDescriptor(0, "a.bin", 1L, "0".repeat(64))),
                    ),
                )
                fail("must reject book id '$bad'")
            } catch (expected: IllegalArgumentException) {
                // expected: validation precedes ANY path access
            }
            assertEquals(
                "'$bad' must not mutate the downloads root",
                before,
                root
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .toSet(),
            )
        }
    }

    // ---------- FINDING 6: journal durability artifacts -------------------------------------------

    @Test
    fun staleJournalTempFileIsInertAndCleaned() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        runToCompletion(root, fixture, committer)
        val bookDir = File(root, fixture.manifest.bookId)
        assertTrue(File(bookDir, "download-v1.complete").delete())
        // Interrupted-write artifact: a stale journal .tmp + torn-but-parseable journal.
        Files.write(File(bookDir, "download.journal.tmp").toPath(), "book|ARCHIVE_STAGED".toByteArray())
        Files.write(
            File(bookDir, "download.journal").toPath(),
            "book|\npart\nbook|ARCHIVE_DELETED".toByteArray(),
        )

        val rerun = newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture))

        assertTrue("stale tmp + torn journal must recover safely: $rerun", rerun.succeeded)
        assertTrue(File(bookDir, "download-v1.complete").isFile)
        assertTrue(
            "stale journal temp must be cleaned",
            bookDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    /** Torn/foreign journal + recovery checks (see DownloadEngineCrashMatrixTest) and commit path. */
    @Test
    fun tornJournalLineNeverCausesAnIndexFailure() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val bookDir = File(root, fixture.manifest.bookId)
        bookDir.mkdirs()
        // Torn lines + a valid-but-incomplete book state: the engine must parse safely and
        // re-derive from PENDING (download/extract proceeds; nothing claims completion).
        Files.write(
            File(bookDir, "download.journal").toPath(),
            ("book|\npart\npart|part00.bin\npart|part01.bin|EXTRACTED\nbook|MANIFEST_READY").toByteArray(),
        )
        val report = newEngine(root, MemoryCommitter()).run(DownloadFixtures.fixtureRequest(fixture))
        assertTrue("torn journal must not break recovery: $report", report.succeeded)
        assertTrue(File(bookDir, "download-v1.complete").isFile)
    }

    @Test
    fun tornJournalClaimingRecordsCommittedWithNothingOnDiskIsRefused() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val bookDir = File(root, fixture.manifest.bookId)
        bookDir.mkdirs()
        // A torn journal that CLAIMS completion while the disk is empty: finding 1 says this
        // must NOT fast-path or auto-restart — explicit failure instead.
        Files.write(
            File(bookDir, "download.journal").toPath(),
            ("book|RECORDS_COMMITTED\npart|part00.bin\npart").toByteArray(),
        )
        val report = newEngine(root, MemoryCommitter()).run(DownloadFixtures.fixtureRequest(fixture))
        assertFalse("claims of completion without proof must fail", report.succeeded)
        assertFalse(File(bookDir, "download-v1.complete").isFile)
    }

    /** Corrupt the journal AFTER commit; recovery preserves file contents, count, and record. */
    @Test
    fun corruptedJournalAfterCommitRecoveryPreservesEverything() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        runToCompletion(root, fixture, committer)
        val bookDir = File(root, fixture.manifest.bookId)
        val managedDir = File(bookDir, "managed")
        val baseline = linkedMapOf<String, String>()
        fixture.partContents.forEach { (name, bytes) -> baseline[name] = DownloadHashes.sha256(bytes) }
        assertEquals(2, managedDir.listFiles().orEmpty().size)

        Files.write(
            File(bookDir, "download.journal").toPath(),
            ("book|\npart\npart|part01.bin|EXTRACTED\nbook|RECORDS_COMMITTED").toByteArray(),
        )
        assertTrue(File(bookDir, "download-v1.complete").delete())

        val rerun = newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture))

        assertTrue("torn-corrupt journal recovery must converge: $rerun", rerun.succeeded)
        assertEquals(2, managedDir.listFiles().orEmpty().size)
        baseline.forEach { (name, hash) ->
            val f = File(managedDir, name)
            assertTrue(DownloadHashes.fileMatchesSha256(f, hash))
            assertTrue(DownloadHashes.fileMatchesSha256(f, DownloadHashes.sha256(fixture.partContents[name]!!)))
        }
        assertEquals(1, committer.committed.size)
        assertTrue(File(bookDir, "download-v1.complete").isFile)
        assertTrue(!File(bookDir, "staging").exists() || File(bookDir, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun inconsistentJournalRefusesToAutoRestart() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        runToCompletion(root, fixture, committer)
        val bookDir = File(root, fixture.manifest.bookId)
        assertTrue(File(bookDir, "download-v1.complete").delete())
        Files.write(File(bookDir, "download.journal").toPath(), "this is not a download journal @@@".toByteArray())

        val rerun = newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture))

        assertFalse("foreign journal must be refused", rerun.succeeded)
        assertTrue("refusal must name the journal", rerun.failedFiles.containsKey("journal"))
        assertEquals(2, File(bookDir, "managed").listFiles().orEmpty().size)
        assertEquals(1, committer.committed.size)
        assertFalse(File(bookDir, "download-v1.complete").isFile)
    }

    @Test
    fun tamperedExtractedPartIsReExtractedNotMoved() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val request = DownloadFixtures.fixtureRequest(fixture)
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
    fun commitConflictFailsWithoutMarker() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        // Committer whose existing record conflicts (different title) -> engine must fail the commit.
        val conflicting =
            MemoryCommitter().apply {
                committed +=
                    MemoryCommitter.Record(
                        fixture.manifest.bookId,
                        "DIFFERENT TITLE",
                        listOf(CommittedPart(0, "part00.bin", "file:///x", 1L, "1".repeat(64))),
                    )
            }
        val request = DownloadFixtures.fixtureRequest(fixture)
        val report = newEngine(root, conflicting).run(request)

        assertFalse("identity conflict must fail the run", report.succeeded)
        assertFalse(File(root, "${fixture.manifest.bookId}/download-v1.complete").isFile)
    }

    @Test
    fun commitFailurePropagates() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter().apply { failCommit = true }
        val report = newEngine(root, committer).run(DownloadFixtures.fixtureRequest(fixture))
        assertFalse(report.succeeded)
        assertNotNull(report.engineFailure)
        assertFalse(File(root, "${fixture.manifest.bookId}/download-v1.complete").exists())
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
                listOf(PartDescriptor(0, "part00.bin", 4L, DownloadHashes.sha256("data".toByteArray()))),
            )
        val report = newEngine(root, MemoryCommitter()).run(request)
        assertFalse(report.succeeded)
        assertTrue(report.engineFailure!!.message!!.contains("no manifest"))
    }
}
