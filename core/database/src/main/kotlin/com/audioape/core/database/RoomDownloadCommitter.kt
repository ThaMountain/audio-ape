package com.audioape.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.audioape.core.download.CommitResult
import com.audioape.core.download.CommittedPart
import com.audioape.core.download.DownloadCommitter
import com.audioape.core.model.BookId
import com.audioape.core.model.MediaPartId
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

/**
 * AA-021: Room-backed identity-aware [DownloadCommitter] (reviewer finding 3).
 *
 * Idempotency criterion is the COMPLETE identity set, not the bare book ID:
 *   - exact replay (same title + same part set: order, content URI, bytes, hash) -> AlreadyPresent, zero mutation
 *   - book row exists with missing/different parts                              -> Conflict, zero mutation
 *   - no book row                                                               -> atomic insert (Committed)
 * The compare-or-insert runs inside one @Transaction ([DownloadCommitDao.commitOrCompare]) so a
 * concurrent reader can never observe a half commit; the UNIQUE (book_id, part_order) index is
 * the constraint backstop.
 */
class RoomDownloadCommitter(
    private val db: AudioApeDatabase,
) : DownloadCommitter {
    override fun verify(
        bookId: String,
        displayTitle: String,
        parts: List<CommittedPart>,
    ): Boolean =
        runBlocking {
            val book = db.libraryBookDao().book(BookId(bookId)) ?: return@runBlocking false
            if (book.displayTitle != displayTitle) return@runBlocking false
            db.downloadCommitDao().partsMatch(BookId(bookId), parts)
        }

    override fun commit(
        bookId: String,
        displayTitle: String,
        parts: List<CommittedPart>,
    ): CommitResult =
        try {
            val id = BookId(bookId)
            val entities =
                parts.map { part ->
                    MediaPartEntity(
                        partId = MediaPartId(UUID.randomUUID().toString()),
                        bookId = id,
                        partOrder = part.order,
                        contentUri = part.contentUri,
                        cachedDurationMs = null,
                        bytes = part.bytes,
                        sha256 = part.sha256Hex,
                        formatHint = null,
                    )
                }
            val outcome =
                runBlocking {
                    db.downloadCommitDao().commitOrCompare(
                        LibraryBookEntity(
                            bookId = id,
                            editionId = null,
                            displayTitle = displayTitle,
                            importedAt = Instant.now(),
                            lastListenedAt = null,
                            finishedAt = null,
                            userOwned = false,
                        ),
                        entities,
                        parts,
                    )
                }
            when (outcome) {
                1 -> CommitResult.Committed
                2 -> CommitResult.AlreadyPresent
                else -> CommitResult.Conflict("existing book record does not match the proposed commit")
            }
        } catch (commitFailed: Throwable) {
            CommitResult.Failed(commitFailed)
        }
}

/**
 * Transactional seam: compare-or-insert for book + parts.
 * Returns 1 = inserted, 2 = exact replay (AlreadyPresent), 3 = identity conflict.
 */
@Dao
abstract class DownloadCommitDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertBookUnchecked(book: LibraryBookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPartsUnchecked(parts: List<MediaPartEntity>)

    @Query("SELECT * FROM library_book WHERE book_id = :bookId")
    protected abstract suspend fun bookUnchecked(bookId: BookId): LibraryBookEntity?

    @Query("SELECT * FROM media_part WHERE book_id = :bookId ORDER BY part_order")
    protected abstract suspend fun partsUnchecked(bookId: BookId): List<MediaPartEntity>

    /** Identity comparison against the proposed commit (order, uri, bytes, hash). */
    protected open fun partsMatchEntity(
        rows: List<MediaPartEntity>,
        proposed: List<CommittedPart>,
    ): Boolean {
        if (rows.size != proposed.size) return false
        val byOrder = rows.associateBy { it.partOrder }
        return proposed.all { part ->
            val row = byOrder[part.order] ?: return false
            row.contentUri == part.contentUri &&
                row.bytes == part.bytes &&
                row.sha256 == part.sha256Hex
        }
    }

    @Query("SELECT * FROM media_part WHERE book_id = :bookId ORDER BY part_order")
    abstract suspend fun partsForBook(bookId: BookId): List<MediaPartEntity>

    /** Allows [verify] to run without inserting anything. */
    open suspend fun partsMatch(
        bookId: BookId,
        proposed: List<CommittedPart>,
    ): Boolean = partsMatchEntity(partsUnchecked(bookId), proposed)

    @Transaction
    open suspend fun commitOrCompare(
        book: LibraryBookEntity,
        entities: List<MediaPartEntity>,
        proposed: List<CommittedPart>,
    ): Int {
        val existing = bookUnchecked(book.bookId)
        if (existing != null) {
            return if (existing.displayTitle == book.displayTitle &&
                partsMatchEntity(partsUnchecked(book.bookId), proposed)
            ) {
                2 // exact replay
            } else {
                3 // identity conflict
            }
        }
        insertBookUnchecked(book)
        insertPartsUnchecked(entities)
        return 1
    }
}
