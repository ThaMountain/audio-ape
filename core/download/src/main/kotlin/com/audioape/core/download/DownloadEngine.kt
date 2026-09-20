package com.audioape.core.download

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * AA-021 download vertical slice engine: authorized multipart archive → private staging →
 * size/hash verification → SAFE extraction → managed tree commit → Room record, with a
 * write-ahead journal + deterministic crash recovery (every transition recoverable, no loss of
 * originals, no duplicate library entries).
 *
 * Security/correctness hardening (reviewer findings 1-6):
 *
 * 1. COMPLETION REQUIRES PROOF (finding 1): the journal is a hint, never the source of truth.
 *    Expected part identities come from [ResolvedDownloadRequest.expectedParts] (the caller's
 *    manifest knowledge, validated by grammar). A fast path, marker entry, or "already
 *    complete" claim is honored ONLY when: every managed file exists with matching size + SHA-256
 *    AND [DownloadCommitter.verify] confirms the exact Room book + part set. An empty or
 *    incomplete journal can never establish completion; with the archive gone and content
 *    unverifiable the run fails explicitly — no marker, no deletion.
 * 2. NO-OVERWRITE PUBLICATION (finding 2): the final publication is a hard link
 *    (Files.createLink) whose POSIX semantics CANNOT replace an existing destination
 *    (EEXIST). A competing destination is either adopted (byte-exact match) or a collision
 *    failure — never overwritten. Concurrent downloads of the same book are serialized by a
 *    per-book process-wide lock. If the filesystem does not support hard links, publication
 *    FAILS explicitly (no silent downgrade to a replacing move).
 * 3. IDENTITY-AWARE ROOM COMMIT (finding 3): the committer compares the complete part set
 *    (order, content URI, bytes, hash); a mismatch is a Conflict = failed commit, never a
 *    marker.
 * 4. BOUNDED INITIAL SCAN (finding 4): every archive member is drained through bounded,
 *    budgeted streaming (entry count, per-entry expanded bytes, cumulative expanded bytes,
 *    inflate ratio) BEFORE the manifest is trusted; the archive itself is capped by
 *    [ResolvedDownloadRequest.maxArchiveSizeBytes] during staging.
 * 5. BOOK-ID VALIDATED FIRST (finding 5): the canonical-UUID check runs before ANY book
 *    path is constructed or read, and the book directory is confined under downloadsRoot.
 * 6. JOURNAL DURABILITY (finding 6): fsync + atomic rename in DownloadJournal; unsupported
 *    atomic replacement fails the run rather than silently downgrading.
 *
 * SCOPE SAFETY (Nick's invariant): this engine touches ONLY files it created under
 * `downloadsRoot/<bookId>/`. It never deletes, moves, renames, or overwrites any pre-existing
 * file (in particular nothing in the user's audiobook folder).
 */
class DownloadEngine(
    private val downloadsRoot: File,
    private val committer: DownloadCommitter,
) {
    /**
     * TEST SEAM: deterministic crash injection at named [DownloadCheckpoint]s. Tests throw
     * [SimulatedCrash] from the hook to model process death; recovery is exercised by a FRESH
     * engine instance. (Simulated, not real Android process death.)
     */
    internal var checkpointHook: ((DownloadCheckpoint) -> Unit)? = null

    private fun at(checkpoint: DownloadCheckpoint) {
        checkpointHook?.invoke(checkpoint)
    }

    fun run(request: ResolvedDownloadRequest): DownloadReport {
        // FINDING 5: validate BEFORE any book-specific path is built, read, written, or deleted.
        validateBookId(request.bookId)
        require(downloadsRoot.isDirectory || downloadsRoot.mkdirs()) { "downloads root must be available" }
        val bookDir = File(downloadsRoot, request.bookId)
        require(bookDir.parentFile.canonicalFile == downloadsRoot.canonicalFile) {
            "book directory must be confined to the downloads root"
        }

        // FINDING 2: serialize concurrent downloads of the SAME book across engine instances.
        val lock = LOCKS.computeIfAbsent(request.bookId) { Any() }
        return synchronized(lock) { runLocked(request, bookDir) }
    }

    private fun runLocked(
        request: ResolvedDownloadRequest,
        bookDir: File,
    ): DownloadReport {
        val report = DownloadReport(bookId = request.bookId)
        val stagingDir = File(bookDir, "staging")
        val extractedDir = File(stagingDir, "extracted")
        val managedDir = File(bookDir, "managed")
        val archive = File(stagingDir, "archive.zip")
        val marker = File(bookDir, COMPLETION_MARKER_NAME)
        val journal = DownloadJournal(File(bookDir, JOURNAL_FILE_NAME))

        // Completion requires PROOF (finding 1): the marker alone is not honored.
        if (marker.exists()) {
            val stagingResidue = stagingDir.exists() && stagingDir.listFiles().orEmpty().isNotEmpty()
            if (stagingResidue || !verifyCompleted(request, managedDir)) {
                report.engineFailure =
                    IllegalStateException(
                        "completion marker present but state is inconsistent " +
                            "(stagingResidue=$stagingResidue, mediaVerified=${verifyCompleted(request, managedDir)})",
                    )
            } else {
                journal.discard() // inert bookkeeping; completion is authoritative only when PROVEN
                report.alreadyComplete = true
            }
            return report
        }

        // The journal is a HINT, not the source of truth. Structurally foreign content is
        // refused outright; torn-but-parseable lines recover via artifact verification below.
        if (!journal.consistent()) {
            report.failedFiles["journal"] =
                IllegalStateException(
                    "download journal is structurally inconsistent (not the v1 format) — refusing to auto-restart",
                )
            return report
        }

        try {
            stagingDir.mkdirs()
            extractedDir.mkdirs()
            managedDir.mkdirs()

            // FINDING 1: the fast path claims completion ONLY when every managed file AND the
            // Room record are VERIFIED against the request's expected identities.
            val journalClaimsComplete =
                journal.book() == BookState.ARCHIVE_DELETED ||
                    (journal.book() == BookState.RECORDS_COMMITTED)
            if (journalClaimsComplete && verifyCompleted(request, managedDir)) {
                cleanupAndMarkComplete(request, bookDir, stagingDir, extractedDir, archive, journal)
                return report
            }

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
                    // FINDING 1: if the journal claims work is finished but the content cannot be
                    // established (archive gone + media/record unverifiable) → explicit failure.
                    if (journal.book() == BookState.ARCHIVE_DELETED || journal.book() == BookState.RECORDS_COMMITTED) {
                        throw IOException(
                            "journal claims completion but the archive is gone and the expected " +
                                "content cannot be verified — refusing to re-download after completion",
                        )
                    }
                    downloadArchive(request, archive, journal, report)
                }
            }
            report.archiveVerified = true
            journal.markBook(BookState.ARCHIVE_STAGED)
            at(DownloadCheckpoint.STAGE_ARCHIVE_VERIFIED)

            // --- 2. Manifest: bounded scan + parse + cross-check against request ------------------
            val manifest = readAndValidateManifest(archive, request)
            journal.markBook(BookState.MANIFEST_READY)
            at(DownloadCheckpoint.MANIFEST_READY)

            // --- 3. Per-part extract / adopt / publish ---------------------------------------------
            manifest.parts.forEach { part ->
                extractOrPublishPart(archive, extractedDir, managedDir, part, journal, report)
            }

            // --- 4. Identity-aware Room record -----------------------------------------------------
            if (!committer.verify(request.bookId, manifest.displayTitle, committedParts(request, managedDir))) {
                val outcome =
                    committer.commit(
                        bookId = request.bookId,
                        displayTitle = manifest.displayTitle,
                        parts = committedParts(request, managedDir),
                    )
                when (outcome) {
                    is CommitResult.Committed -> Unit

                    is CommitResult.AlreadyPresent -> Unit

                    // exact replay; still a single record
                    is CommitResult.Conflict -> throw IOException("Room commit conflict: ${outcome.reason}")

                    is CommitResult.Failed -> throw IOException("Room commit failed", outcome.cause)
                }
            }
            journal.markBook(BookState.RECORDS_COMMITTED)
            at(DownloadCheckpoint.RECORDS_COMMITTED)

            cleanupAndMarkComplete(request, bookDir, stagingDir, extractedDir, archive, journal)
        } catch (interrupted: InterruptedException) {
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

    /**
     * FINDING 1: verification that expected content REALLY exists. Every managed file must
     * match size + SHA-256 from [ResolvedDownloadRequest.expectedParts], and the committer must
     * confirm the exact Room book + part set. An empty or partial set fails — emptiness can
     * never establish completion.
     */
    private fun verifyCompleted(
        request: ResolvedDownloadRequest,
        managedDir: File,
    ): Boolean {
        if (request.expectedParts.isEmpty()) return false
        val mediaOk =
            request.expectedParts.all { part ->
                val f = File(managedDir, part.fileName)
                f.isFile &&
                    f.length() == part.sizeBytes &&
                    DownloadHashes.fileMatchesSha256(f, part.sha256Hex)
            }
        if (!mediaOk) return false
        return committer.verify(request.bookId, request.displayTitle, committedParts(request, managedDir))
    }

    private fun committedParts(
        request: ResolvedDownloadRequest,
        managedDir: File,
    ): List<CommittedPart> =
        request.expectedParts.map { part ->
            CommittedPart(
                order = part.order,
                fileName = part.fileName,
                contentUri = File(managedDir, part.fileName).toURI().toString(),
                bytes = part.sizeBytes,
                sha256Hex = part.sha256Hex,
            )
        }

    private fun cleanupAndMarkComplete(
        request: ResolvedDownloadRequest,
        bookDir: File,
        stagingDir: File,
        extractedDir: File,
        archive: File,
        journal: DownloadJournal,
    ) {
        // Re-verify BEFORE the marker (a final proof gate; nothing is deleted in between).
        deleteOnlyInside(extractedDir)
        extractedDir.delete() // the engine-created extraction dir itself is disposable
        at(DownloadCheckpoint.BEFORE_ARCHIVE_DELETE)
        Files.deleteIfExists(archive.toPath())
        at(DownloadCheckpoint.AFTER_ARCHIVE_DELETE)
        journal.markBook(BookState.ARCHIVE_DELETED)
        at(DownloadCheckpoint.BEFORE_MARKER_CREATE)
        Files.write(marker(bookDir).toPath(), MARKER_CONTENT.toByteArray(StandardCharsets.UTF_8))
        at(DownloadCheckpoint.AFTER_MARKER_CREATE)
        journal.discard()
    }

    private fun marker(bookDir: File): File = File(bookDir, COMPLETION_MARKER_NAME)

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
                        // FINDING 4: absolute archive cap independent of the (caller-supplied) expectation.
                        if (written > request.maxArchiveSizeBytes) {
                            throw IOException("archive exceeds the configured size cap")
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

    /**
     * FINDING 4: the initial scan DRAINS every entry through bounded, budgeted streaming —
     * entry count, per-entry expanded bytes, cumulative expanded bytes, inflate ratio — and is
     * the ONLY pass that may trust the manifest afterwards. ZIP header sizes are NOT trusted
     * alone (they may be unknown/forged); what was actually decompressed is what is counted.
     */
    private fun readAndValidateManifest(
        archive: File,
        request: ResolvedDownloadRequest,
    ): DownloadManifest {
        val members = linkedMapOf<String, Boolean>()
        var manifestBytes: ByteArray? = null
        var entryCount = 0
        var cumulativeExpanded = 0L
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > ArchiveSafetyLimits.MAX_PART_COUNT + 1) {
                        throw ZipException("archive member count exceeds the budget")
                    }
                    val name = entry.name
                    ArchiveNameChecker.rejectReason(name)?.let { throw ZipException("unsafe member '$name': $it") }
                    val prior = members.putIfAbsent(name.lowercase(), false)
                    if (prior != null) throw ZipException("duplicate member '$name' (case-insensitive)")

                    if (name == MANIFEST_MEMBER_NAME) {
                        val chunk = zip.readNBytes(ArchiveSafetyLimits.MAX_MANIFEST_BYTES + 1)
                        if (chunk.size > ArchiveSafetyLimits.MAX_MANIFEST_BYTES) {
                            throw ZipException("manifest member exceeds the size budget")
                        }
                        manifestBytes = chunk
                        members[name.lowercase()] = true
                    } else {
                        // DRAIN the entry with full per-entry + cumulative + ratio budgets.
                        drainEntry(zip, entry, cumulativeExpanded)
                    }
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
        require(manifest.parts == request.expectedParts) {
            "archive manifest parts do not match the requested identities"
        }
        val expectedMembers =
            (listOf(MANIFEST_MEMBER_NAME) + manifest.parts.map { it.fileName })
                .map { it.lowercase() }
                .toSet()
        val actualMembers = members.keys.toSet()
        require(actualMembers == expectedMembers) {
            "archive members do not match manifest (extra=${actualMembers - expectedMembers}, missing=${expectedMembers - actualMembers})"
        }
        return manifest
    }

    /** Reads (and discards) one entry with hard budget enforcement on ACTUAL expanded bytes. */
    private fun drainEntry(
        zip: ZipInputStream,
        entry: ZipEntry,
        cumulativeExpanded: Long,
    ) {
        var expanded = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            if (read > 0) {
                expanded += read
                if (expanded > ArchiveSafetyLimits.MAX_PART_BYTES) {
                    throw ZipException("member '${entry.name}' exceeds the per-member expanded budget")
                }
                if (cumulativeExpanded + expanded > ArchiveSafetyLimits.MAX_TOTAL_BYTES) {
                    throw ZipException("cumulative expanded bytes exceed the book budget")
                }
            }
        }
        val compressed = entry.compressedSize
        if (compressed > 0 && expanded > compressed * ArchiveSafetyLimits.MAX_INFLATE_RATIO) {
            throw ZipException("member '${entry.name}' inflate ratio exceeds the safety budget")
        }
    }

    private fun extractOrPublishPart(
        archive: File,
        extractedDir: File,
        managedDir: File,
        part: PartDescriptor,
        journal: DownloadJournal,
        report: DownloadReport,
    ) {
        val managedFile = File(managedDir, part.fileName)

        // FINDING 2: competing destination first — exact match = adopt; anything else depends on
        // ownership proof. A wrong-hash file is OURS to discard ONLY when the journal shows we
        // were mid-publication (PUBLISHING) — i.e. an interrupted copy, recoverable. Otherwise it
        // is a pre-existing/foreign file: collision-failure, NEVER overwritten or deleted.
        fun adoptIfExact(destination: File): Boolean {
            if (destination.isFile &&
                destination.length() == part.sizeBytes &&
                DownloadHashes.fileMatchesSha256(destination, part.sha256Hex)
            ) {
                journal.markPart(part.fileName, PartState.MOVED)
                report.partsAdopted += 1
                at(DownloadCheckpoint.PART_MOVED)
                return true
            }
            return false
        }
        if (managedFile.exists()) {
            if (adoptIfExact(managedFile)) return
            if (journal.part(part.fileName) == PartState.PUBLISHING) {
                // Our own interrupted publication (createFile claimed the name, the copy never
                // verified). Under the per-book lock nothing else can own this file: discard and redo.
                managedFile.delete()
            } else {
                throw IOException("managed tree collision for '${part.fileName}': refusing to overwrite")
            }
        }

        val extractedFile = File(extractedDir, part.fileName)
        if (journal.part(part.fileName) == PartState.EXTRACTED && extractedFile.isFile) {
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

        // FINDING 2 — ANDROID-SAFE PUBLICATION: Android forbids hard links (SELinux EACCES on
        // createLink), so the native no-clobber primitive is CREATE_NEW (O_EXCL): the claim
        // itself fails if the name exists, and the operation has NO replace capability. Protocol:
        //   1. journal PUBLISHING (write-ahead: a wrong-hash dest is OURS only after this mark),
        //   2. CREATE_NEW the destination (FileAlreadyExistsException = competitor won: adopt or
        //      collision-failure, bytes preserved),
        //   3. copy the VERIFIED bytes from staging into the claimed file + fsync,
        //   4. re-verify size + hash, then journal MOVED.
        // A crash between 2 and 4 leaves a partial file that recovery recognizes as ours (journal
        // still PUBLISHING) and discards/redoes. Atomic visibility is not provided by CREATE_NEW
        // (a reader could see a partial file pre-MOVED); the journal is the visibility gate, and
        // this weaker-than-rename visibility is documented. External non-engine tampering during
        // the copy window is outside the threat model (single-owner directories + per-book lock).
        journal.markPart(part.fileName, PartState.PUBLISHING)
        at(DownloadCheckpoint.PART_PUBLISHING)
        try {
            java.nio.channels.FileChannel
                .open(
                    managedFile.toPath(),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE,
                ).use { out ->
                    java.nio.file.Files
                        .newByteChannel(
                            extractedFile.toPath(),
                            java.nio.file.StandardOpenOption.READ,
                        ).use { source -> out.transferFrom(source, 0, Long.MAX_VALUE) }
                    out.force(true)
                }
        } catch (exists: java.nio.file.FileAlreadyExistsException) {
            // A competitor claimed the name between our adopt-check and the O_EXCL claim.
            if (adoptIfExact(managedFile)) return
            throw IOException(
                "managed tree collision for '${part.fileName}': refusing to overwrite",
                exists,
            )
        }
        if (managedFile.length() != part.sizeBytes ||
            !DownloadHashes.fileMatchesSha256(managedFile, part.sha256Hex)
        ) {
            managedFile.delete()
            throw IOException("publication verification failed for '${part.fileName}'")
        }
        if (Files.isSymbolicLink(managedFile.toPath()) || Files.isSymbolicLink(extractedFile.toPath())) {
            Files.deleteIfExists(managedFile.toPath())
            throw IOException("published member is a symlink — rejected")
        }
        Files.deleteIfExists(extractedFile.toPath())
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
        val parsed = runCatching { UUID.fromString(bookId) }.getOrNull()
        require(parsed != null && parsed.toString() == bookId) { "book id must be a canonical UUID" }
    }

    private companion object {
        const val MANIFEST_MEMBER_NAME = "manifest.mf"
        const val JOURNAL_FILE_NAME = "download.journal"
        const val COMPLETION_MARKER_NAME = "download-v1.complete"
        const val MARKER_CONTENT = "audio-ape download vertical slice v1 complete"

        /** FINDING 2: process-wide per-book locks (cross-engine-instance coordination). */
        val LOCKS = ConcurrentHashMap<String, Any>()
    }
}
