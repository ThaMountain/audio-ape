package com.audioape.core.download

/**
 * Persistence seam for the download commit (AA-021). The engine is pure JVM; the Room-backed
 * implementation lives in :core:database (RoomDownloadCommitter).
 */
interface DownloadCommitter {
    /** True iff a library record already exists for this book (idempotent recovery adopt). */
    fun recordExists(bookId: String): Boolean

    /**
     * Inserts the book + its parts atomically. Returns [CommitResult.AlreadyPresent] when the
     * book is already recorded — the engine treats that as success and ADOPTS without touching
     * anything (no duplicates by construction + journal-driven single-shot).
     */
    fun commit(
        bookId: String,
        displayTitle: String,
        parts: List<CommittedPart>,
    ): CommitResult
}

sealed interface CommitResult {
    data object Committed : CommitResult

    data object AlreadyPresent : CommitResult

    data class Failed(
        val cause: Throwable,
    ) : CommitResult
}
