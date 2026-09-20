package com.audioape.core.download

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Deterministic synthetic multipart archives for AA-021 (authorized/public-domain-style fixture
 * data — plain bytes in JVM tests; the instrumented E2E swaps in playable WAVs). All hashes and
 * sizes are computed at build time so requests are always consistent with the archive.
 */
object DownloadFixtures {
    data class SyntheticZip(
        val zipFile: File,
        val archiveSizeBytes: Long,
        val archiveSha256Hex: String,
        val manifest: DownloadManifest,
        val partContents: Map<String, ByteArray>,
    )

    fun deterministicBytes(
        seed: Int,
        size: Int,
    ): ByteArray = ByteArray(size) { i -> ((seed * 31 + i) % 251).toByte() }

    fun syntheticBook(
        dir: File,
        bookId: String = UUID.randomUUID().toString(),
        title: String = "Synthetic Vertical Slice",
        parts: List<Pair<String, ByteArray>> =
            listOf("part00.bin" to deterministicBytes(1, 4096), "part01.bin" to deterministicBytes(2, 8192)),
    ): SyntheticZip {
        val descriptors =
            parts.mapIndexed { index, (name, bytes) ->
                PartDescriptor(
                    order = index,
                    fileName = name,
                    sizeBytes = bytes.size.toLong(),
                    sha256Hex = DownloadHashes.sha256(bytes),
                )
            }
        val manifest = DownloadManifest(bookId, title, descriptors)
        val zip = File(dir, "book.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.mf"))
            zos.write(DownloadManifestCodec.encode(manifest).toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
            parts.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return SyntheticZip(
            zipFile = zip,
            archiveSizeBytes = zip.length(),
            archiveSha256Hex = DownloadHashes.sha256(zip.inputStream()),
            manifest = manifest,
            partContents = parts.toMap(),
        )
    }

    /** Writes an arbitrary member list (malicious variants in safety tests). */
    fun writeZipWithMembers(
        dir: File,
        members: List<Pair<String, ByteArray>>,
    ): File {
        val zip = File(dir, "book.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            members.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zip
    }

    /**
     * Standard request for a built fixture (expected parts = manifest parts; the trustworthy
     * identity source the engine verifies against).
     */
    fun fixtureRequest(
        fixture: SyntheticZip,
        bookId: String = fixture.manifest.bookId,
        title: String = fixture.manifest.displayTitle,
        source: DownloadSource = DownloadSource { fixture.zipFile.inputStream() },
        maxArchiveSizeBytes: Long = ArchiveSafetyLimits.MAX_ARCHIVE_BYTES,
    ): ResolvedDownloadRequest =
        ResolvedDownloadRequest(
            bookId = bookId,
            displayTitle = title,
            source = source,
            expectedArchiveSizeBytes = fixture.archiveSizeBytes,
            expectedArchiveSha256Hex = fixture.archiveSha256Hex,
            expectedParts = fixture.manifest.parts,
            maxArchiveSizeBytes = maxArchiveSizeBytes,
        )
}

/** In-memory committer for headless tests; tracks identity sets to prove single-record commit. */
internal class MemoryCommitter : DownloadCommitter {
    data class Record(
        val bookId: String,
        val displayTitle: String,
        val parts: List<CommittedPart>,
    )

    val committed = mutableListOf<Record>()
    var failCommit = false

    override fun verify(
        bookId: String,
        displayTitle: String,
        parts: List<CommittedPart>,
    ): Boolean = committed.any { it.bookId == bookId && it.displayTitle == displayTitle && it.parts == parts }

    override fun commit(
        bookId: String,
        displayTitle: String,
        parts: List<CommittedPart>,
    ): CommitResult =
        if (failCommit) {
            CommitResult.Failed(IllegalStateException("simulated commit failure"))
        } else {
            val existing = committed.firstOrNull { it.bookId == bookId }
            when {
                existing == null -> {
                    committed += Record(bookId, displayTitle, parts)
                    CommitResult.Committed
                }

                existing.parts == parts && existing.displayTitle == displayTitle -> {
                    CommitResult.AlreadyPresent
                }

                else -> {
                    CommitResult.Conflict("existing parts differ from the proposed commit")
                }
            }
        }
}
