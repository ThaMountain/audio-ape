package com.audioape.core.download

/**
 * Persistence seam for the download commit (AA-021). The engine is pure JVM; the Room-backed
 * implementation lives in :core:database (RoomDownloadCommitter).
 *
 * Identity-aware semantics (reviewer finding 3): "book exists" is NOT idempotency. An exact
 * replay (same book + same COMPLETE part set: order, content URI, bytes, hash) is
 * [CommitResult.AlreadyPresent] with no mutation; an existing book whose parts differ or are
 * missing is [CommitResult.Conflict] — never overwritten, never merged, never duplicated. The
 * compare-or-insert decision must be transactional in the implementation.
 */
interface DownloadCommitter {
    /**
     * True iff a library record matching [bookId] with EXACTLY [parts] (identity fields) exists.
     * Used for completion verification (finding 1): a marker or journal claim is only honored
     * when the persisted book AND every part row match the expected identities.
     */
    fun verify(
        bookId: String,
        displayTitle: String,
        parts: List<CommittedPart>,
    ): Boolean

    /**
     * Compare-or-insert, atomically:
     *  - no book row           -> insert book + parts, return [CommitResult.Committed]
     *  - exact match (replay)  -> return [CommitResult.AlreadyPresent], zero mutation
     *  - row exists, mismatch  -> return [CommitResult.Conflict], zero mutation
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

    data class Conflict(
        val reason: String,
    ) : CommitResult

    data class Failed(
        val cause: Throwable,
    ) : CommitResult
}
