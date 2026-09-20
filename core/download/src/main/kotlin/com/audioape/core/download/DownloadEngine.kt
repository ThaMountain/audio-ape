package com.audioape.core.download

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * AA-021 download vertical slice engine: authorized multipart archive → private staging →
 * size/hash verification → SAFE extraction → managed tree commit → Room record, with a
 * write-ahead journal + deterministic crash recovery (every transition recoverable, no loss of
 * originals, no duplicate library entries).
 *
 * SCOPE SAFETY (Nick's invariant): this engine touches ONLY files it created under
 * `downloadsRoot/<bookId>/` (the subtree it owns). It never deletes, moves, renames, or overwrites any pre-existing
 * file (in particular nothing in the user's audiobook folder). A managed-tree file collision
 * (exists + different hash) is an explicit FAILURE, never an overwrite; a collision with
 * matching hash is adopted (idempotent recovery).
 */
class DownloadEngine(
    private val downloadsRoot: File,
    private val committer: DownloadCommitter,
) {
    /**
     * TEST SEAM: deterministic crash injection at named [DownloadCheckpoint]s (see
     * DownloadJournal.kt). Tests throw [SimulatedCrash] from the hook to model process death;
     * recovery is then exercised by a FRESH engine instance.
     */
    internal var checkpointHook: ((DownloadCheckpoint) -> Unit)? = null

    private fun at(checkpoint: DownloadCheckpoint) {
        checkpointHook?.invoke(checkpoint)
    }

    fun run(request: ResolvedDownloadRequest): DownloadReport {
        val report = DownloadReport(bookId = request.bookId)
        val bookDir = File(downloadsRoot, request.bookId)
        val stagingDir = File(bookDir, "staging")
        val extractedDir = File(stagingDir, "extracted")
        val managedDir = File(bookDir, "managed")
        val archive = File(stagingDir, "archive.zip")
        val marker = File(bookDir, COMPLETION_MARKER_NAME)
        val journal = DownloadJournal(File(bookDir, JOURNAL_FILE_NAME))

        // --- Completion-marker entry ---------------------------------------------------------
        if (marker.exists()) {
            val stagingResidue = stagingDir.exists() && stagingDir.listFiles().orEmpty().isNotEmpty()
            if (stagingResidue || !committer.recordExists(request.bookId)) {
                report.engineFailure =
                    IllegalStateException(
                        "completion marker present but state is inconsistent " +
                            "(stagingResidue=$stagingResidue, recordExists=${committer.recordExists(request.bookId)})",
                    )
            } else {
                // Marker proves completion; a lingering journal is inert bookkeeping — discard it.
                journal.discard()
                report.alreadyComplete = true
            }
            return report
        }

        // The journal is a HINT, not the source of truth. A structurally inconsistent journal
        // (foreign/garbage content — NOT our format) is refused outright: never silently
        // interpret arbitrary malformed data as permission to restart work. A torn-but-parseable
        // line is recoverable (skipped; PENDING default) because every resume/reuse/discard
        // decision below VERIFIES the actual artifacts (size + hash) before touching anything.
        if (!journal.consistent()) {
            report.failedFiles["journal"] =
                IllegalStateException(
                    "download journal is structurally inconsistent (not the v1 format) — refusing to auto-restart",
                )
            return report
        }

        try {
            validateBookId(request.bookId)
            bookDir.mkdirs()
            stagingDir.mkdirs()
            extractedDir.mkdirs()
            managedDir.mkdirs()

            // --- Recovery fast path -----------------------------------------------------------
            // If the journal already proves every part MOVED and the record committed, only the
            // cleanup + marker remain — never re-download, never re-extract, never re-commit.
            val fastPath =
                journal.book() == BookState.ARCHIVE_DELETED ||
                    (journal.book() == BookState.RECORDS_COMMITTED && journal.allPartsMoved())
            if (!fastPath) {
                // --- 1. Staging: archive present + verified? otherwise (re)download ------------------
                when {
                    archive.isFile && DownloadHashes.fileMatchesSha256(archive, request.expectedArchiveSha256Hex) -> {
                        require(archive.length() == request.expectedArchiveSizeBytes) {
                            "staged archive size mismatch"
                        }
                    }

                    archive.isFile -> {
                        throw IOException(
                            "staged archive exists but its hash does not match the expected digest",
                        )
                    }

                    else -> {
                        downloadArchive(request, archive, journal, report)
                    }
                }
                report.archiveVerified = true
                journal.markBook(BookState.ARCHIVE_STAGED)
                at(DownloadCheckpoint.STAGE_ARCHIVE_VERIFIED)

                // --- 2. Manifest: read + validate + cross-check against the request -------------------
                val manifest = readAndValidateManifest(archive, request)
                journal.markBook(BookState.MANIFEST_READY)
                at(DownloadCheckpoint.MANIFEST_READY)

                // --- 3. Per-part extract / adopt / move -----------------------------------------------
                manifest.parts.forEach { part ->
                    extractOrAdoptPart(archive, stagingDir, extractedDir, managedDir, part, journal, report)
                }

                // --- 4. Room record -------------------------------------------------------------------
                if (!committer.recordExists(request.bookId)) {
                    val outcome =
                        committer.commit(
                            bookId = request.bookId,
                            displayTitle = manifest.displayTitle,
                            parts =
                                manifest.parts.map { part ->
                                    CommittedPart(
                                        order = part.order,
                                        fileName = part.fileName,
                                        contentUri = managedPart(managedDir, part.fileName).toURI().toString(),
                                        bytes = part.sizeBytes,
                                        sha256Hex = part.sha256Hex,
                                    )
                                },
                        )
                    when (outcome) {
                        is CommitResult.Committed -> Unit

                        is CommitResult.AlreadyPresent -> Unit

                        // adopted; still a single record
                        is CommitResult.Failed -> throw IOException("Room commit failed", outcome.cause)
                    }
                }
                journal.markBook(BookState.RECORDS_COMMITTED)
                at(DownloadCheckpoint.RECORDS_COMMITTED)
            }

            // --- 5. Cleanup + marker (marker is the LAST durable artifact) --------------------------
            deleteOnlyInside(extractedDir)
            at(DownloadCheckpoint.BEFORE_ARCHIVE_DELETE)
            Files.deleteIfExists(archive.toPath())
            at(DownloadCheckpoint.AFTER_ARCHIVE_DELETE)
            journal.markBook(BookState.ARCHIVE_DELETED)
            at(DownloadCheckpoint.BEFORE_MARKER_CREATE)
            // Staging must be FULLY gone so a later marker check sees zero residue: drop the now-empty
            // extracted/ and the staging/ container itself (both engine-created), not just their contents.
            Files.deleteIfExists(extractedDir.toPath())
            Files.deleteIfExists(stagingDir.toPath())
            Files.write(marker.toPath(), MARKER_CONTENT.toByteArray(StandardCharsets.UTF_8))
            at(DownloadCheckpoint.AFTER_MARKER_CREATE)
            journal.discard()
            // Marker entry check for the AFTER_MARKER_CREATE crash is handled next run: marker
            // present + clean -> verified no-op. The lingering journal is discarded there.
        } catch (interrupted: InterruptedException) {
            // Preserve the interrupt status so the harness/thread sees the interruption,
            // then propagate — never masked as an ordinary download failure.
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (simulatedCrash: SimulatedCrash) {
            throw simulatedCrash // a crash must look like a crash: no cleanup from the dead process
        } catch (fatal: java.lang.VirtualMachineError) {
            throw fatal // OOM/linkage/etc. must never be converted into an ordinary download failure
        } catch (fatal: ThreadDeath) {
            throw fatal
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled // cancellation must propagate to the caller, not be masked
        } catch (failure: Throwable) {
            // Detailed stack is retained in the report for the debug/test channel only;
            // production surfaces a sanitized message without logging sensitive URLs/paths.
            report.engineFailure = failure
        }
        return report
    }

    private fun downloadArchive(
        request: ResolvedDownloadRequest,
        archive: File,
        journal: DownloadJournal,
        report: DownloadReport,
    ) {
        val tmp = File(archive.parentFile, "archive.zip.tmp")
        Files.deleteIfExists(tmp.toPath())
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var written = 0L
        request.source.openStream().use { input ->
            tmp.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (written > request.expectedArchiveSizeBytes) {
                            throw IOException("archive exceeds the expected size")
                        }
                    }
                }
            }
        }
        if (written != request.expectedArchiveSizeBytes) {
            throw IOException("archive size ${written}B != expected ${request.expectedArchiveSizeBytes}B")
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != request.expectedArchiveSha256Hex) {
            throw IOException("archive sha256 mismatch (expected ${request.expectedArchiveSha256Hex}, got $hex)")
        }
        at(DownloadCheckpoint.STAGE_ARCHIVE_TMP_WRITTEN)
        Files.move(tmp.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readAndValidateManifest(
        archive: File,
        request: ResolvedDownloadRequest,
    ): DownloadManifest {
        val membersWithManifest = linkedMapOf<String, Boolean>()
        var manifestBytes: ByteArray? = null
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    ArchiveNameChecker.rejectReason(name)?.let { throw ZipException("unsafe member '$name': $it") }
                    val prior = membersWithManifest.putIfAbsent(name.lowercase(), false)
                    if (prior != null) throw ZipException("duplicate member '$name' (case-insensitive)")
                    if (name == MANIFEST_MEMBER_NAME) {
                        if (entry.size > ArchiveSafetyLimits.MAX_MANIFEST_BYTES) {
                            throw ZipException("manifest member too large")
                        }
                        manifestBytes = zip.readNBytes(ArchiveSafetyLimits.MAX_MANIFEST_BYTES + 1)
                        if (manifestBytes!!.size > ArchiveSafetyLimits.MAX_MANIFEST_BYTES) {
                            throw ZipException("manifest member exceeds the size budget")
                        }
                        membersWithManifest[name.lowercase()] = true
                    }
                    // Keep the stream positioned; sizes are re-checked during extraction.
                }
            }
        } catch (zip: ZipException) {
            throw IOException("malformed archive: ${zip.message}", zip)
        }
        val bytes = manifestBytes ?: throw IOException("archive has no $MANIFEST_MEMBER_NAME member")
        val manifest = DownloadManifestCodec.decode(String(bytes, StandardCharsets.UTF_8))
        require(manifest.bookId == request.bookId) {
            "archive manifest book-id '${manifest.bookId}' does not match requested '${request.bookId}'"
        }
        // The archive must contain EXACTLY the manifest + its declared parts — nothing else.
        val expectedMembers =
            (listOf(MANIFEST_MEMBER_NAME) + manifest.parts.map { it.fileName })
                .map { it.lowercase() }
                .toSet()
        val actualMembers = membersWithManifest.keys.toSet()
        require(actualMembers == expectedMembers) {
            "archive members do not match manifest (extra=${actualMembers - expectedMembers}, missing=${expectedMembers - actualMembers})"
        }
        return manifest
    }

    private fun extractOrAdoptPart(
        archive: File,
        stagingDir: File,
        extractedDir: File,
        managedDir: File,
        part: PartDescriptor,
        journal: DownloadJournal,
        report: DownloadReport,
    ) {
        val managedFile = managedPart(managedDir, part.fileName)
        // Managed-file collision/adopt gate: NEVER overwrite. Match -> adopt, mismatch -> fail.
        if (managedFile.exists()) {
            if (managedFile.length() == part.sizeBytes && DownloadHashes.fileMatchesSha256(managedFile, part.sha256Hex)) {
                journal.markPart(part.fileName, PartState.MOVED)
                report.partsAdopted += 1
                at(DownloadCheckpoint.PART_MOVED)
                return
            }
            throw IOException("managed tree collision for '${part.fileName}': refusing to overwrite")
        }

        val extractedFile = File(extractedDir, part.fileName)
        if (journal.part(part.fileName) == PartState.EXTRACTED && extractedFile.isFile) {
            // Journal says EXTRACTED — but the journal is a hint, not truth. VERIFY the artifact
            // (size + hash) before reusing it; a torn journal may claim EXTRACTED for a partial
            // or tampered file, and moving garbage into the managed tree is not acceptable.
            if (extractedFile.length() == part.sizeBytes &&
                DownloadHashes.fileMatchesSha256(extractedFile, part.sha256Hex)
            ) {
                // verified — reuse below
            } else {
                extractedFile.delete() // discard the unreliable artifact, re-extract from the archive
                journal.markPart(part.fileName, PartState.EXTRACTING)
                at(DownloadCheckpoint.PART_EXTRACTING)
                extractMember(archive, part, extractedFile)
                journal.markPart(part.fileName, PartState.EXTRACTED)
                at(DownloadCheckpoint.PART_EXTRACTED)
            }
        } else {
            journal.markPart(part.fileName, PartState.EXTRACTING)
            at(DownloadCheckpoint.PART_EXTRACTING)
            extractMember(archive, part, extractedFile)
            journal.markPart(part.fileName, PartState.EXTRACTED)
            at(DownloadCheckpoint.PART_EXTRACTED)
        }
        // Commit into the managed tree (atomic move, NO replace: refusal on collision).
        try {
            Files.move(
                extractedFile.toPath(),
                managedFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (fileAlreadyExists: java.nio.file.FileAlreadyExistsException) {
            if (managedFile.length() == part.sizeBytes && DownloadHashes.fileMatchesSha256(managedFile, part.sha256Hex)) {
                journal.markPart(part.fileName, PartState.MOVED)
                report.partsAdopted += 1
                return
            }
            throw IOException("managed tree collision for '${part.fileName}': refusing to overwrite", fileAlreadyExists)
        }
        if (Files.isSymbolicLink(managedFile.toPath())) {
            Files.deleteIfExists(managedFile.toPath())
            throw IOException("extracted member materialized as a symlink — rejected")
        }
        journal.markPart(part.fileName, PartState.MOVED)
        report.partsCommitted += 1
        at(DownloadCheckpoint.PART_MOVED)
    }

    /**
     * Streams ONE member out of the archive with hash verification inside the same pass.
     * Bounds checked: per-member size, inflate ratio (zip-bomb), real disk bytes.
     */
    private fun extractMember(
        archive: File,
        part: PartDescriptor,
        target: File,
    ) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var inflated = 0L
        var matched = false
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name != part.fileName) continue
                    matched = true
                    require(!entry.isDirectory) { "part '${part.fileName}' is a directory entry" }
                    require(entry.size <= ArchiveSafetyLimits.MAX_PART_BYTES) { "part exceeds per-member budget" }
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    Files.deleteIfExists(tmp.toPath())
                    tmp.outputStream().buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                out.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                inflated += read
                                require(inflated <= part.sizeBytes) { "part '${part.fileName}' exceeds declared size" }
                            }
                        }
                    }
                    // Inflate-ratio guard: cheap guard against 1000:1 compression bombs.
                    val compressed = entry.compressedSize
                    if (compressed > 0 && inflated > compressed * ArchiveSafetyLimits.MAX_INFLATE_RATIO) {
                        Files.deleteIfExists(tmp.toPath())
                        throw IOException("part '${part.fileName}' inflate ratio exceeds the safety budget")
                    }
                    if (inflated != part.sizeBytes) {
                        Files.deleteIfExists(tmp.toPath())
                        throw IOException("part '${part.fileName}' size ${inflated}B != declared ${part.sizeBytes}B")
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hex != part.sha256Hex) {
                        Files.deleteIfExists(tmp.toPath())
                        throw IOException("part '${part.fileName}' sha256 mismatch")
                    }
                    if (Files.isSymbolicLink(tmp.toPath())) {
                        Files.deleteIfExists(tmp.toPath())
                        throw IOException("extracted member materialized as a symlink — rejected")
                    }
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            }
        } catch (zip: ZipException) {
            // Android reports CRC failures via CRCException (a ZipException subclass);
            // OpenJDK reports them as ZipException — one catch covers both runtimes.
            Files.deleteIfExists(target.toPath())
            throw IOException("malformed archive reading '${part.fileName}': ${zip.message}", zip)
        }
        if (!matched) {
            throw IOException("archive has no member named '${part.fileName}'")
        }
    }

    private fun managedPart(
        managedDir: File,
        fileName: String,
    ): File = File(managedDir, fileName)

    /** Deletes ONLY regular files/dirs the engine created inside [dir]; refuses to follow links. */
    private fun deleteOnlyInside(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles().orEmpty().forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) {
                throw IOException("refusing to delete through a symlink inside staging")
            }
            if (child.isDirectory) {
                deleteOnlyInside(child)
                child.delete()
            } else {
                child.delete()
            }
        }
    }

    private fun validateBookId(bookId: String) {
        val parsed = runCatching { java.util.UUID.fromString(bookId) }.getOrNull()
        require(parsed != null && parsed.toString() == bookId) { "book id must be a canonical UUID" }
    }

    private companion object {
        const val MANIFEST_MEMBER_NAME = "manifest.mf"
        const val JOURNAL_FILE_NAME = "download.journal"
        const val COMPLETION_MARKER_NAME = "download-v1.complete"
        const val MARKER_CONTENT = "audio-ape download vertical slice v1 complete"
    }
}
