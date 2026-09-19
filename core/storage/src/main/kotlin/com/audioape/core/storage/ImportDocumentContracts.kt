package com.audioape.core.storage

import com.audioape.core.model.ContentReference

data class SafDocumentMetadata(
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
) {
    init {
        require(sizeBytes == null || sizeBytes >= 0L) { "document size must be nonnegative" }
    }
}

/** Read-only metadata needed to plan and verify an import without traversing the whole library. */
interface SafImportDocumentInspection {
    fun inspectDocument(document: ContentReference): SafDocumentMetadata

    fun listChildDisplayNames(parent: ContentReference): Set<String>
}

/**
 * A destination reference created by [SafeCopyImporter]. It cannot represent an import source.
 */
@JvmInline
value class ImporterOwnedDestination internal constructor(
    val document: ContentReference,
)

/**
 * Narrow cleanup capability for a failed import's newly-created destination.
 *
 * This is deliberately separate from [SafDocumentOperations], which remains delete-free.
 */
interface PartialDestinationCleanup {
    fun deletePartialDestination(destination: ImporterOwnedDestination): Boolean
}
