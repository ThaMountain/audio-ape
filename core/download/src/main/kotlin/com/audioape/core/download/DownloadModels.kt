package com.audioape.core.download

/**
 * AA-021 download vertical slice — core types.
 *
 * All names and hashes are validated at construction so a malformed manifest or request can
 * never reach the extraction/commit machinery. Every file name in the v1 manifest is restricted
 * to `[A-Za-z0-9._-]` (see [ArchiveSafetyLimits.SAFE_FILE_NAME]), which makes path traversal
 * IMPOSSIBLE by grammar: no separators, no dots that can escape, no drive letters.
 */
data class PartDescriptor(
    val order: Int,
    val fileName: String,
    val sizeBytes: Long,
    val sha256Hex: String,
) {
    init {
        require(order >= 0) { "part order must be nonnegative" }
        require(ArchiveSafetyLimits.SAFE_FILE_NAME.matches(fileName)) {
            "part file name '$fileName' violates the safe charset"
        }
        require(sizeBytes >= 0L) { "part size must be nonnegative" }
        require(SHA256_HEX.matches(sha256Hex)) { "part sha256 must be 64 lowercase hex characters" }
    }
}

/**
 * v1 manifest: what a downloaded multipart archive is expected to contain.
 * Parsed from the archive's `manifest.mf` member and cross-checked against the request so a
 * source cannot substitute a different book than the one the caller asked for.
 */
data class DownloadManifest(
    val bookId: String,
    val displayTitle: String,
    val parts: List<PartDescriptor>,
) {
    init {
        require(bookId.isNotBlank()) { "manifest book id must not be blank" }
        require(displayTitle.isNotBlank()) { "manifest title must not be blank" }
        require(displayTitle.length <= MAX_TITLE_CHARS) { "manifest title is too long" }
        validatePartList(parts)
    }
}

/** A source the engine can pull the archive from (HTTP, file, content provider, ...). */
fun interface DownloadSource {
    fun openStream(): java.io.InputStream
}

/** What the caller knows before downloading: identity + cryptographic expectations. */
data class ResolvedDownloadRequest(
    val bookId: String,
    val displayTitle: String,
    val source: DownloadSource,
    val expectedArchiveSizeBytes: Long,
    val expectedArchiveSha256Hex: String,
    val expectedParts: List<PartDescriptor>,
    val maxArchiveSizeBytes: Long = ArchiveSafetyLimits.MAX_ARCHIVE_BYTES,
) {
    init {
        require(bookId.isNotBlank()) { "book id must not be blank" }
        require(displayTitle.isNotBlank()) { "display title must not be blank" }
        require(expectedArchiveSizeBytes >= 0L) { "expected archive size must be nonnegative" }
        require(SHA256_HEX.matches(expectedArchiveSha256Hex)) {
            "expected archive sha256 must be 64 lowercase hex characters"
        }
        validatePartList(expectedParts)
        require(maxArchiveSizeBytes >= ArchiveSafetyLimits.MAX_MANIFEST_BYTES) {
            "archive size cap must be a sane bound"
        }
    }
}

/**
 * Shared structural validation for a part list (used by the manifest AND the request's
 * expected parts, so the two can never disagree structurally). The safe-file-name grammar
 * makes traversal impossible by construction.
 */
internal fun validatePartList(parts: List<PartDescriptor>) {
    require(parts.isNotEmpty()) { "part list must not be empty" }
    require(parts.size <= ArchiveSafetyLimits.MAX_PART_COUNT) { "too many parts" }
    require(parts.map { it.order }.distinct().size == parts.size) { "duplicate part orders" }
    require(parts.map { it.fileName.lowercase() }.distinct().size == parts.size) {
        "duplicate part file names (case-insensitive)"
    }
    require(parts.sumOf { it.sizeBytes } <= ArchiveSafetyLimits.MAX_TOTAL_BYTES) {
        "declared total size exceeds the book budget"
    }
    parts.forEach { part ->
        require(part.sizeBytes <= ArchiveSafetyLimits.MAX_PART_BYTES) { "part exceeds the per-member budget" }
    }
}

/** One part committed into the managed tree, as handed to the Room committer. */
data class CommittedPart(
    val order: Int,
    val fileName: String,
    val contentUri: String,
    val bytes: Long,
    val sha256Hex: String,
)

/** Aggregate result of one download run. */
data class DownloadReport(
    val bookId: String,
    var archiveVerified: Boolean = false,
    var partsCommitted: Int = 0,
    var partsAdopted: Int = 0,
    var alreadyComplete: Boolean = false,
    var failedFiles: MutableMap<String, Throwable> = linkedMapOf(),
    var engineFailure: Throwable? = null,
) {
    val succeeded: Boolean
        get() = failedFiles.isEmpty() && engineFailure == null
}

internal val SHA256_HEX = Regex("[0-9a-f]{64}")

internal const val MAX_TITLE_CHARS = 300
