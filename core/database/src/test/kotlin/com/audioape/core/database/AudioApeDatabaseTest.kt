package com.audioape.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.audioape.core.model.BookId
import com.audioape.core.model.BookmarkId
import com.audioape.core.model.ChapterId
import com.audioape.core.model.EditionId
import com.audioape.core.model.MediaFormatHint
import com.audioape.core.model.MediaPartId
import com.audioape.core.model.WorkId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioApeDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: AudioApeDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AudioApeDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun booksPartsAndChaptersCanBeQueriedAndObserved() =
        runBlocking {
            val seed = seedBook(1)
            val part = part(seed.bookId, 1, order = 0)
            val chapter = chapter(seed.bookId, 1, startMs = 0, endMs = 60_000)

            database.mediaPartDao().insertParts(listOf(part))
            database.chapterDao().replaceChapters(seed.bookId, listOf(chapter))

            assertEquals(listOf(seed.book), database.libraryBookDao().observeBooks().first())
            assertEquals(listOf(part), database.mediaPartDao().observeParts(seed.bookId).first())
            assertEquals(listOf(chapter), database.chapterDao().observeChapters(seed.bookId).first())
            assertEquals(seed.book, database.libraryBookDao().book(seed.bookId))
        }

    @Test
    fun foreignKeysAndUniqueKeysRejectInvalidRows(): Unit =
        runBlocking {
            val first = seedBook(2)
            val second = seedBook(3)
            database.mediaPartDao().insertParts(listOf(part(first.bookId, 2, order = 0)))

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    database.mediaPartDao().insertParts(listOf(part(first.bookId, 3, order = 0)))
                }
            }
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    database.mediaPartDao().insertParts(listOf(part(BookId(uuid("missing")), 4, 0)))
                }
            }

            val alias = ExternalAliasEntity("fixture", "catalog", "same-id", first.editionId)
            database.catalogDao().insertExternalAliases(listOf(alias))
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    database.catalogDao().insertExternalAliases(
                        listOf(alias.copy(editionId = second.editionId)),
                    )
                }
            }
        }

    @Test
    fun deletingOneBookCascadesOnlyItsPrivateListeningRowsAndParts() =
        runBlocking {
            val deleted = seedBook(4)
            val retained = seedBook(5)
            database.mediaPartDao().insertParts(
                listOf(part(deleted.bookId, 5, 0), part(retained.bookId, 6, 0)),
            )
            database.playbackCheckpointDao().saveCheckpoints(
                listOf(checkpoint(deleted.bookId, 4_000), checkpoint(retained.bookId, 5_000)),
            )
            database.bookmarkDao().insertBookmark(bookmark(deleted.bookId, 1))
            database.bookmarkDao().insertBookmark(bookmark(retained.bookId, 2))

            assertEquals(1, database.libraryBookDao().deleteBook(deleted.bookId))

            assertNull(database.libraryBookDao().book(deleted.bookId))
            assertEquals(emptyList<MediaPartEntity>(), database.mediaPartDao().parts(deleted.bookId))
            assertNull(database.playbackCheckpointDao().checkpoint(deleted.bookId))
            assertEquals(emptyList<BookmarkEntity>(), database.bookmarkDao().bookmarks(deleted.bookId))

            assertNotNull(database.libraryBookDao().book(retained.bookId))
            assertEquals(1, database.mediaPartDao().parts(retained.bookId).size)
            assertNotNull(database.playbackCheckpointDao().checkpoint(retained.bookId))
            assertEquals(1, database.bookmarkDao().bookmarks(retained.bookId).size)
        }

    @Test
    fun checkpointBatchRoundTripsAndAlwaysRestoresPaused() =
        runBlocking {
            val first = seedBook(6)
            val second = seedBook(7)
            database.playbackCheckpointDao().saveCheckpoints(
                listOf(
                    checkpoint(first.bookId, 12_345).copy(lastPlayingIntent = true),
                    checkpoint(second.bookId, 67_890),
                ),
            )

            val restoredFirst = requireNotNull(database.playbackCheckpointDao().checkpoint(first.bookId))
            val restoredSecond = requireNotNull(database.playbackCheckpointDao().checkpoint(second.bookId))
            assertEquals(12_345L, restoredFirst.positionMs)
            assertEquals(67_890L, restoredSecond.positionMs)
            assertFalse(restoredFirst.lastPlayingIntent)
            assertFalse(restoredSecond.lastPlayingIntent)
        }

    @Test
    fun latestCheckpointUsesMostRecentPlaybackTimestamp() =
        runBlocking {
            val older = seedBook(60)
            val newer = seedBook(61)
            database.playbackCheckpointDao().saveCheckpoints(
                listOf(
                    checkpoint(older.bookId, 1_000L),
                    checkpoint(newer.bookId, 2_000L),
                ),
            )

            assertEquals(newer.bookId, database.playbackCheckpointDao().latestCheckpoint()?.bookId)
        }

    @Test
    fun fileDatabasePersistsAcrossCloseAndReopen() =
        runBlocking {
            database.close()
            val name = "reopen-${uuid("database")}.db"
            context.deleteDatabase(name)
            try {
                database = AudioApeDatabase.create(context, name)
                val seed = seedBook(8)
                database.mediaPartDao().insertParts(listOf(part(seed.bookId, 8, 0)))
                database.close()

                database = AudioApeDatabase.create(context, name)
                assertEquals(seed.book, database.libraryBookDao().book(seed.bookId))
                assertEquals(1, database.mediaPartDao().parts(seed.bookId).size)
            } finally {
                database.close()
                context.deleteDatabase(name)
                database =
                    Room
                        .inMemoryDatabaseBuilder(context, AudioApeDatabase::class.java)
                        .allowMainThreadQueries()
                        .build()
            }
        }

    @Test
    fun duplicateDetectionUsesExactSha256AcrossBooks() =
        runBlocking {
            val first = seedBook(9)
            val second = seedBook(10)
            val different = seedBook(11)
            val duplicateHash = "a".repeat(64)
            database.mediaPartDao().insertParts(
                listOf(
                    part(first.bookId, 9, 0, duplicateHash),
                    part(second.bookId, 10, 0, duplicateHash),
                    part(different.bookId, 11, 0, "b".repeat(64)),
                ),
            )

            assertEquals(
                listOf(first.bookId, second.bookId),
                database.mediaPartDao().booksContainingHash(duplicateHash).map { it.bookId },
            )
        }

    @Test
    fun oneBookCanBelongToMultipleCollectionsWithoutDuplicateMembership(): Unit =
        runBlocking {
            val seed = seedBook(12)
            val first = collection(1, "Favorites")
            val second = collection(2, "Road trips")
            database.collectionDao().insertCollection(first)
            database.collectionDao().insertCollection(second)
            database.collectionDao().addBook(collectionItem(first.collectionId, seed.bookId))
            database.collectionDao().addBook(collectionItem(second.collectionId, seed.bookId))

            assertEquals(
                listOf(first, second),
                database.collectionDao().observeCollectionsForBook(seed.bookId).first(),
            )
            assertEquals(
                listOf(seed.book),
                database.collectionDao().observeBooksInCollection(first.collectionId).first(),
            )
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    database.collectionDao().addBook(collectionItem(first.collectionId, seed.bookId))
                }
            }
        }

    @Test
    fun chapterReplacementRejectsOverlappingOrOpenMiddleSpans() =
        runBlocking {
            val seed = seedBook(13)
            val valid =
                listOf(
                    chapter(seed.bookId, 13, 0, 10_000),
                    chapter(seed.bookId, 14, 10_000, null),
                )
            database.chapterDao().replaceChapters(seed.bookId, valid)

            val invalid =
                listOf(
                    chapter(seed.bookId, 15, 0, null),
                    chapter(seed.bookId, 16, 10_000, 20_000),
                )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { database.chapterDao().replaceChapters(seed.bookId, invalid) }
            }
            assertEquals(valid, database.chapterDao().chapters(seed.bookId))
        }

    @Test
    fun ftsSearchContainsMetadataButNoListeningState() =
        runBlocking {
            val seed = seedBook(14)
            database.librarySearchDao().replace(
                LibrarySearchContentEntity(
                    bookId = seed.bookId,
                    title = "A Long Voyage",
                    authors = "Ada Author",
                    narrator = "Noah Narrator",
                    series = "Voyages",
                ),
            )

            assertEquals(listOf(seed.book), database.librarySearchDao().search("Narrator").first())
        }

    private suspend fun seedBook(index: Int): Seed {
        val workId = WorkId(uuid("work-$index"))
        val editionId = EditionId(uuid("edition-$index"))
        val bookId = BookId(uuid("book-$index"))
        val work =
            WorkEntity(
                workId = workId,
                title = "Work $index",
                authors = listOf("Author $index"),
                seriesReference = if (index % 2 == 0) "Series" else null,
                seriesOrder = if (index % 2 == 0) index.toDouble() else null,
            )
        val edition =
            AudioEditionEntity(
                editionId = editionId,
                workId = workId,
                narrator = "Narrator $index",
                language = "en",
                durationMs = 100_000,
                abridged = false,
                publisher = "Fixture Publisher",
                publicationDate = LocalDate.of(2020, 1, 1),
                confirmedAudioEvidence = "fixture://confirmed-audio/$index",
            )
        val book =
            LibraryBookEntity(
                bookId = bookId,
                editionId = editionId,
                displayTitle = "Book $index",
                importedAt = Instant.ofEpochMilli(index * 1_000L),
                lastListenedAt = null,
                finishedAt = null,
                userOwned = true,
            )
        database.catalogDao().insertWork(work)
        database.catalogDao().insertEdition(edition)
        database.libraryBookDao().insertBook(book)
        return Seed(editionId, bookId, book)
    }

    private fun part(
        bookId: BookId,
        index: Int,
        order: Int,
        sha256: String = index.toString(16).padStart(64, '0'),
    ) = MediaPartEntity(
        partId = MediaPartId(uuid("part-$index")),
        bookId = bookId,
        partOrder = order,
        contentUri = "content://fixture/book/${bookId.value}/$index",
        cachedDurationMs = 100_000,
        bytes = 1_024,
        sha256 = sha256,
        formatHint = MediaFormatHint(container = "m4b", codec = "aac"),
    )

    private fun chapter(
        bookId: BookId,
        index: Int,
        startMs: Long,
        endMs: Long?,
    ) = ChapterEntity(
        chapterId = ChapterId(uuid("chapter-$index")),
        bookId = bookId,
        startMs = startMs,
        endMs = endMs,
        title = "Chapter $index",
        provenance = "fixture",
        acceptedOnline = false,
    )

    private fun checkpoint(
        bookId: BookId,
        positionMs: Long,
    ) = PlaybackCheckpointEntity(
        bookId = bookId,
        positionMs = positionMs,
        lastPlayedAt = Instant.ofEpochMilli(positionMs),
        speed = 1.25f,
    )

    private fun bookmark(
        bookId: BookId,
        index: Int,
    ) = BookmarkEntity(
        id = BookmarkId(uuid("bookmark-$index")),
        bookId = bookId,
        positionMs = index * 1_000L,
        label = "Bookmark $index",
        note = null,
        createdAt = Instant.ofEpochMilli(index * 1_000L),
    )

    private fun collection(
        index: Int,
        name: String,
    ) = CollectionEntity(
        collectionId = uuid("collection-$index"),
        name = name,
        createdAt = Instant.ofEpochMilli(index * 1_000L),
    )

    private fun collectionItem(
        collectionId: String,
        bookId: BookId,
    ) = CollectionItemEntity(collectionId, bookId, Instant.EPOCH)

    private data class Seed(
        val editionId: EditionId,
        val bookId: BookId,
        val book: LibraryBookEntity,
    )
}

private fun uuid(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
