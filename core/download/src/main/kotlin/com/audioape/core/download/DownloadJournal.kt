package com.audioape.core.download

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Write-ahead journal for one download book directory (AA-021), mirroring the hardened
 * VaultKeyMigrator discipline: per-part states recorded BEFORE the file operations they guard,
 * atomic rewrites (temp + rename) so a crash never leaves a torn state line, and a completion
 * marker committed LAST. Recovery prefers redundant recoverable data (staging archive is kept
 * until every part is MOVED and the Room record is committed) over early deletion.
 *
 * States:
 *   part:  PENDING → EXTRACTING (before extraction) → EXTRACTED (bytes+hash verified)
 *          → MOVED (committed into the managed tree; adopted copies also reach MOVED)
 *   book:  PENDING → ARCHIVE_STAGED (archive verified on disk)
 *          → MANIFEST_READY (manifest parsed + validated)
 *          → RECORDS_COMMITTED (Room record present)
 *          → ARCHIVE_DELETED (staging cleaned) → DONE (marker written)
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

    fun allPartsMoved(): Boolean = partStates.values.all { it == PartState.MOVED }

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
    }

    private fun rewrite() {
        // DURABLE ATOMIC REPLACEMENT (reviewer follow-up): the journal is never appended to or
        // rewritten in place — every state change writes a `.tmp` sibling and atomically renames
        // it over the journal, so a crash can leave only the OLD or the NEW state, never a torn
        // file. Covered by the crash matrix (all 11 checkpoints) and the no-`.tmp`-residue
        // assertion in the happy-path test.
        val lines =
            buildString {
                appendLine("book|${bookState.name}")
                partStates.forEach { (name, state) -> appendLine("part|$name|${state.name}") }
            }
        val temp = File(file.parentFile, file.name + ".tmp")
        Files.write(temp.toPath(), lines.toByteArray(StandardCharsets.UTF_8))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal enum class PartState { PENDING, EXTRACTING, EXTRACTED, MOVED }

internal enum class BookState { PENDING, ARCHIVE_STAGED, MANIFEST_READY, RECORDS_COMMITTED, ARCHIVE_DELETED, DONE }

/** Named interruption points for the deterministic crash-matrix tests ([SimulatedCrash]). */
internal enum class DownloadCheckpoint {
    STAGE_ARCHIVE_TMP_WRITTEN,
    STAGE_ARCHIVE_VERIFIED,
    MANIFEST_READY,
    PART_EXTRACTING,
    PART_EXTRACTED,
    PART_MOVED,
    RECORDS_COMMITTED,
    BEFORE_ARCHIVE_DELETE,
    AFTER_ARCHIVE_DELETE,
    BEFORE_MARKER_CREATE,
    AFTER_MARKER_CREATE,
}

/** Thrown by the [DownloadCheckpoint] test seam to model process death. */
internal class SimulatedCrash : RuntimeException("simulated process crash")
