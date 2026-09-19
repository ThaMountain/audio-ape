package com.audioape.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.audioape.core.model.BookId
import com.audioape.core.model.EditionId
import com.audioape.core.model.WorkId
import com.audioape.core.model.validateIncreasingChapterSpans
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWork(work: WorkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEdition(edition: AudioEditionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExternalAliases(aliases: List<ExternalAliasEntity>)

    @Query("SELECT * FROM work WHERE work_id = :workId")
    suspend fun work(workId: WorkId): WorkEntity?

    @Query("SELECT * FROM audio_edition WHERE edition_id = :editionId")
    suspend fun edition(editionId: EditionId): AudioEditionEntity?

    @Query(
        "SELECT * FROM external_alias WHERE edition_id = :editionId " +
            "ORDER BY provider, alias_type, value",
    )
    suspend fun aliases(editionId: EditionId): List<ExternalAliasEntity>
}

@Dao
interface LibraryBookDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: LibraryBookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBooks(books: List<LibraryBookEntity>)

    @Query("SELECT * FROM library_book WHERE book_id = :bookId")
    suspend fun book(bookId: BookId): LibraryBookEntity?

    @Query("SELECT * FROM library_book WHERE edition_id = :editionId ORDER BY imported_at, book_id")
    suspend fun copiesOfEdition(editionId: EditionId): List<LibraryBookEntity>

    @Query(
        "SELECT * FROM library_book " +
            "ORDER BY COALESCE(last_listened_at, imported_at) DESC, book_id",
    )
    fun observeBooks(): Flow<List<LibraryBookEntity>>

    @Query("SELECT * FROM library_book WHERE book_id = :bookId")
    fun observeBook(bookId: BookId): Flow<LibraryBookEntity?>

    /** Child rows use FK cascade; this statement can target only the supplied canonical book ID. */
    @Query("DELETE FROM library_book WHERE book_id = :bookId")
    suspend fun deleteBook(bookId: BookId): Int
}

@Dao
interface MediaPartDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParts(parts: List<MediaPartEntity>)

    @Query("SELECT * FROM media_part WHERE book_id = :bookId ORDER BY part_order")
    suspend fun parts(bookId: BookId): List<MediaPartEntity>

    @Query("SELECT * FROM media_part WHERE book_id = :bookId ORDER BY part_order")
    fun observeParts(bookId: BookId): Flow<List<MediaPartEntity>>

    @Query(
        "SELECT DISTINCT b.* FROM library_book AS b " +
            "INNER JOIN media_part AS p ON p.book_id = b.book_id " +
            "WHERE p.sha256 = :sha256 ORDER BY b.imported_at, b.book_id",
    )
    suspend fun booksContainingHash(sha256: String): List<LibraryBookEntity>
}

@Dao
abstract class ChapterDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertUnchecked(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapter WHERE book_id = :bookId")
    protected abstract suspend fun deleteForBookUnchecked(bookId: BookId)

    @Query("SELECT * FROM chapter WHERE book_id = :bookId ORDER BY start_ms")
    abstract suspend fun chapters(bookId: BookId): List<ChapterEntity>

    @Query("SELECT * FROM chapter WHERE book_id = :bookId ORDER BY start_ms")
    abstract fun observeChapters(bookId: BookId): Flow<List<ChapterEntity>>

    @Transaction
    open suspend fun replaceChapters(
        bookId: BookId,
        chapters: List<ChapterEntity>,
    ) {
        require(chapters.all { it.bookId == bookId }) { "all chapters must belong to the target book" }
        validateIncreasingChapterSpans(chapters.map(ChapterEntity::toModel))
        deleteForBookUnchecked(bookId)
        insertUnchecked(chapters)
    }
}

@Dao
abstract class PlaybackCheckpointDao {
    @Upsert
    protected abstract suspend fun upsertUnchecked(checkpoints: List<PlaybackCheckpointEntity>)

    @Query("SELECT * FROM playback_checkpoint WHERE book_id = :bookId")
    abstract suspend fun checkpoint(bookId: BookId): PlaybackCheckpointEntity?

    @Query("SELECT * FROM playback_checkpoint WHERE book_id = :bookId")
    abstract fun observeCheckpoint(bookId: BookId): Flow<PlaybackCheckpointEntity?>

    @Query("SELECT * FROM playback_checkpoint ORDER BY last_played_at DESC, book_id LIMIT 1")
    abstract suspend fun latestCheckpoint(): PlaybackCheckpointEntity?

    /**
     * Stores a batch atomically and always clears playing intent. A cold restore is therefore
     * paused even if a caller submits state captured while audio was playing.
     */
    @Transaction
    open suspend fun saveCheckpoints(checkpoints: List<PlaybackCheckpointEntity>) {
        require(checkpoints.map { it.bookId }.distinct().size == checkpoints.size) {
            "a checkpoint batch may contain each book only once"
        }
        upsertUnchecked(checkpoints.map { it.copy(lastPlayingIntent = false) })
    }
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmark WHERE book_id = :bookId ORDER BY position_ms, created_at, id")
    suspend fun bookmarks(bookId: BookId): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark WHERE book_id = :bookId ORDER BY position_ms, created_at, id")
    fun observeBookmarks(bookId: BookId): Flow<List<BookmarkEntity>>
}

@Dao
interface CollectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCollection(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addBook(item: CollectionItemEntity)

    @Query("DELETE FROM collection_item WHERE collection_id = :collectionId AND book_id = :bookId")
    suspend fun removeBook(
        collectionId: String,
        bookId: BookId,
    ): Int

    @Query(
        "SELECT c.* FROM collection AS c " +
            "INNER JOIN collection_item AS i ON i.collection_id = c.collection_id " +
            "WHERE i.book_id = :bookId ORDER BY c.name, c.collection_id",
    )
    fun observeCollectionsForBook(bookId: BookId): Flow<List<CollectionEntity>>

    @Query(
        "SELECT b.* FROM library_book AS b " +
            "INNER JOIN collection_item AS i ON i.book_id = b.book_id " +
            "WHERE i.collection_id = :collectionId " +
            "ORDER BY COALESCE(b.last_listened_at, b.imported_at) DESC, b.book_id",
    )
    fun observeBooksInCollection(collectionId: String): Flow<List<LibraryBookEntity>>
}

@Dao
interface BookHistoryDao {
    @Upsert
    suspend fun upsert(tombstone: BookHistoryTombstoneEntity)

    @Query(
        "SELECT * FROM book_history_tombstone " +
            "WHERE alias_provider = :provider AND alias_type = :type AND alias_value = :value",
    )
    suspend fun byAlias(
        provider: String,
        type: String,
        value: String,
    ): BookHistoryTombstoneEntity?
}

@Dao
abstract class LibrarySearchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertUnchecked(entry: LibrarySearchContentEntity)

    @Query("DELETE FROM library_search_content WHERE book_id = :bookId")
    protected abstract suspend fun deleteUnchecked(bookId: BookId)

    @Transaction
    open suspend fun replace(entry: LibrarySearchContentEntity) {
        deleteUnchecked(entry.bookId)
        insertUnchecked(entry)
    }

    @Query(
        "SELECT b.* FROM library_book AS b " +
            "INNER JOIN library_search_fts AS f ON f.book_id = b.book_id " +
            "WHERE library_search_fts MATCH :query ORDER BY b.display_title, b.book_id",
    )
    abstract fun search(query: String): Flow<List<LibraryBookEntity>>
}
