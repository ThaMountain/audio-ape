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
 * AA-021: Room-backed [DownloadCommitter]. Commit idempotency rests on STABLE IDENTITIES plus
 * database constraints + a real transaction (reviewer follow-up):
 *   - the book's canonical `bookId` is the primary key (a replayed commit finds the existing
 *     row via the pre-check in [DownloadCommitDao.commitOnce] and reports AlreadyPresent);
 *   - `media_part` has a UNIQUE (book_id, part_order) index, so a duplicate part insert
 *     violates a constraint and aborts the whole transaction instead of creating a second entry;
 *   - [DownloadCommitDao.commitOnce] is a `@Transaction`: book + parts are all-or-nothing.
 * A replay can therefore never produce a duplicate library entry.
 */
class RoomDownloadCommitter(
    private val db: AudioApeDatabase,
) : DownloadCommitter {
    override fun recordExists(bookId: String): Boolean = runBlocking { db.libraryBookDao().book(BookId(bookId)) != null }

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
            val inserted =
                runBlocking {
                    db.downloadCommitDao().commitOnce(
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
                    )
                }
            if (inserted) CommitResult.Committed else CommitResult.AlreadyPresent
        } catch (commitFailed: Throwable) {
            CommitResult.Failed(commitFailed)
        }
}

/**
 * Transactional seam: book + parts insert atomically, idempotent per stable bookId.
 * Returns false (AlreadyPresent) when the book row already exists — nothing is touched.
 */
@Dao
abstract class DownloadCommitDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertBookUnchecked(book: LibraryBookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPartsUnchecked(parts: List<MediaPartEntity>)

    @Query("SELECT * FROM library_book WHERE book_id = :bookId")
    protected abstract suspend fun bookUnchecked(bookId: BookId): LibraryBookEntity?

    @Transaction
    open suspend fun commitOnce(
        book: LibraryBookEntity,
        parts: List<MediaPartEntity>,
    ): Boolean {
        if (bookUnchecked(book.bookId) != null) return false
        insertBookUnchecked(book)
        insertPartsUnchecked(parts)
        return true
    }
}
