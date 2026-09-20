package com.audioape.core.download

import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Write-ahead journal for one download book directory (AA-021), mirroring the hardened
 * VaultKeyMigrator discipline: per-part states recorded BEFORE the file operations they guard,
 * and a completion marker committed LAST. Recovery prefers redundant recoverable data (staging
 * archive is kept until every part is MOVED and the Room record is verified) over early deletion.
 *
 * States:
 *   part:  PENDING → EXTRACTING (before extraction) → EXTRACTED (bytes+hash verified)
 *          → MOVED (committed into the managed tree; adopted copies also reach MOVED)
 *   book:  PENDING → ARCHIVE_STAGED (archive verified on disk)
 *          → MANIFEST_READY (manifest parsed + validated)
 *          → RECORDS_COMMITTED (Room record verified)
 *          → ARCHIVE_DELETED (staging cleaned) → DONE (marker written)
 *
 * DURABILITY CONTRACT (reviewer finding 6): every state change is published by:
 *   1. writing the full new journal to a `.tmp` sibling,
 *   2. forcing the temp file's contents to stable storage (FileChannel.force),
 *   3. an ATOMIC rename over the journal file (old-or-new, never torn),
 *   4. a best-effort directory fsync.
 * If the provider refuses atomic replacement (AtomicMoveNotSupportedException) the write FAILS
 * (propagated) — durability cannot be quietly downgraded; the run then reports an engine
 * failure and retries on the next run, where the OLD journal state is still recoverable.
 * A stale `.tmp` from an interrupted write is inert (never read) and cleaned on next rewrite.
 */
internal class DownloadJournal(
    private val file: File,
) {
    private val partStates = linkedMapOf<String, PartState>()
    private var bookState: BookState = BookState.PENDING
    private var inconsistent = false

    init {
        if (file.exists()) {
            file.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val cols = line.split('|')
                when (cols[0]) {
                    "book" -> {
                        // Torn known-type line (too few fields): recoverable — skip (PENDING default).
                        if (cols.size >= 2) {
                            bookState = BookState.entries.firstOrNull { it.name == cols[1] } ?: BookState.PENDING
                        }
                    }

                    "part" -> {
                        if (cols.size >= 3) {
                            partStates[cols[1]] = PartState.entries.firstOrNull { it.name == cols[2] } ?: PartState.PENDING
                        }
                    }

                    // Unknown prefix / foreign bytes: NOT our journal format at all. This is
                    // structurally inconsistent — explicit failure later, never a silent restart.
                    else -> {
                        inconsistent = true
                    }
                }
            }
        }
    }

    /**
     * True unless the journal file contained lines that are NOT this journal's format. A
     * recoverable torn line (known prefix, too few fields) is NOT inconsistent: recovery
     * re-derives from PENDING + artifact verification. Foreign/garbage content IS.
     */
    fun consistent(): Boolean = !inconsistent

    fun book(): BookState = bookState

    fun part(fileName: String): PartState = partStates[fileName] ?: PartState.PENDING

    fun markBook(state: BookState) {
        bookState = state
        rewrite()
    }

    fun markPart(
        fileName: String,
        state: PartState,
    ) {
        partStates[fileName] = state
        rewrite()
    }

    fun discard() {
        partStates.clear()
        bookState = BookState.PENDING
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(tempFile.toPath())
    }

    private fun rewrite() {
        val lines =
            buildString {
                appendLine("book|${bookState.name}")
                partStates.forEach { (name, state) -> appendLine("part|$name|${state.name}") }
            }
        val bytes = lines.toByteArray(StandardCharsets.UTF_8)
        FileChannel
            .open(tempFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            .use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true) // contents + metadata to stable storage before the rename
            }
        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            // Durability cannot be established atomically: fail loudly rather than downgrade.
            Files.deleteIfExists(tempFile.toPath())
            throw IllegalStateException("journal atomic replacement is not supported by this filesystem", unsupported)
        }
        forceParentDirectory()
    }

    /** Best-effort directory fsync; inability to fsync the dir is tolerated (documented). */
    private fun forceParentDirectory() {
        try {
            FileChannel.open(file.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: UnsupportedOperationException) {
        } catch (_: java.io.IOException) {
        }
    }

    private val tempFile: File = File(file.parentFile, file.name + ".tmp")
}

internal enum class PartState { PENDING, EXTRACTING, EXTRACTED, PUBLISHING, MOVED }

internal enum class BookState { PENDING, ARCHIVE_STAGED, MANIFEST_READY, RECORDS_COMMITTED, ARCHIVE_DELETED, DONE }

/** Named interruption points for the deterministic crash-matrix tests ([SimulatedCrash]). */
internal enum class DownloadCheckpoint {
    STAGE_ARCHIVE_TMP_WRITTEN,
    STAGE_ARCHIVE_VERIFIED,
    MANIFEST_READY,
    PART_EXTRACTING,
    PART_EXTRACTED,
    PART_PUBLISHING,
    PART_MOVED,
    RECORDS_COMMITTED,
    BEFORE_ARCHIVE_DELETE,
    AFTER_ARCHIVE_DELETE,
    BEFORE_MARKER_CREATE,
    AFTER_MARKER_CREATE,
}

/** Thrown by the [DownloadCheckpoint] test seam to model process death. */
internal class SimulatedCrash : RuntimeException("simulated process crash")
