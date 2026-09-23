package com.audioape.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.audioape.core.download.CommitResult
import com.audioape.core.download.CommittedPart
import com.audioape.core.model.BookId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AA-021: Room-backed identity-aware download committer (reviewer finding 3) — idempotency is
 * the COMPLETE identity set (title + part order/URI/bytes/hash), never the bare book ID, and
 * the compare-or-insert runs atomically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomDownloadCommitterTest {
    private lateinit var database: AudioApeDatabase
    private lateinit var committer: RoomDownloadCommitter

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AudioApeDatabase::class.java).allowMainThreadQueries().build()
        committer = RoomDownloadCommitter(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun parts(uriBase: String = "file:///data"): List<CommittedPart> =
        listOf(
            CommittedPart(0, "part00.wav", "$uriBase/part00.wav", 4096L, "0".repeat(64)),
            CommittedPart(1, "part01.wav", "$uriBase/part01.wav", 8192L, "1".repeat(64)),
        )

    @Test
    fun exactReplayIsAlreadyPresentWithoutMutation() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            val first = committer.commit(bookId, "Two Part Download", parts())
            assertTrue("first commit must insert", first is CommitResult.Committed)

            val replay = committer.commit(bookId, "Two Part Download", parts())
            assertTrue("exact replay must be AlreadyPresent", replay is CommitResult.AlreadyPresent)

            assertEquals("exactly one book row", true, database.libraryBookDao().book(BookId(bookId)) != null)
            assertEquals(
                "exactly two part rows across both commits",
                2,
                database.mediaPartDao().parts(BookId(bookId)).size,
            )
            assertTrue(
                "verify() must confirm the exact identity set",
                committer.verify(bookId, "Two Part Download", parts()),
            )
        }

    @Test
    fun existingBookWithZeroPartsIsAConflict() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            // Simulate a book row that exists WITHOUT any media_part rows (partial/foreign state).
            database.libraryBookDao().insertBook(
                LibraryBookEntity(
                    bookId = BookId(bookId),
                    editionId = null,
                    displayTitle = "t",
                    importedAt = java.time.Instant.now(),
                    lastListenedAt = null,
                    finishedAt = null,
                    userOwned = false,
                ),
            )
            val outcome = committer.commit(bookId, "t", parts())
            assertTrue("existing book without parts must be a Conflict", outcome is CommitResult.Conflict)
            assertEquals("no part rows may appear", 0, database.mediaPartDao().parts(BookId(bookId)).size)
            assertEquals("exactly one book row", true, database.libraryBookDao().book(BookId(bookId)) != null)
        }

    @Test
    fun differingHashIsAConflict() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            assertTrue(committer.commit(bookId, "t", parts()) is CommitResult.Committed)
            val corrupted = parts().map { if (it.order == 0) it.copy(sha256Hex = "f".repeat(64)) else it }
            val second = committer.commit(bookId, "t", corrupted)
            assertTrue("differing part hash must be a Conflict", second is CommitResult.Conflict)
            // Pre-existing rows untouched.
            assertEquals(2, database.mediaPartDao().parts(BookId(bookId)).size)
            assertEquals("0".repeat(64), database.mediaPartDao().parts(BookId(bookId))[0].sha256)
        }

    @Test
    fun differingUriIsAConflict() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            assertTrue(committer.commit(bookId, "t", parts()) is CommitResult.Committed)
            val moved = parts(uriBase = "file:///elsewhere")
            assertTrue(
                "differing content URI must be a Conflict",
                committer.commit(bookId, "t", moved) is CommitResult.Conflict,
            )
        }

    @Test
    fun missingPartIsAConflict() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            assertTrue(committer.commit(bookId, "t", parts()) is CommitResult.Committed)
            val partial = parts().dropLast(1)
            assertTrue(
                "missing part must be a Conflict",
                committer.commit(bookId, "t", partial) is CommitResult.Conflict,
            )
            assertEquals(2, database.mediaPartDao().parts(BookId(bookId)).size)
        }

    @Test
    fun differingTitleIsAConflict() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            assertTrue(committer.commit(bookId, "Original Title", parts()) is CommitResult.Committed)
            assertTrue(
                "differing title must be a Conflict",
                committer.commit(bookId, "Renamed Title", parts()) is CommitResult.Conflict,
            )
            assertEquals("Original Title", database.libraryBookDao().book(BookId(bookId))!!.displayTitle)
        }

    @Test
    fun concurrentCommitsYieldExactlyOneInsert() {
        val bookId = UUID.randomUUID().toString()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<CommitResult>())
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) {
            pool.execute {
                start.await()
                outcomes += RoomDownloadCommitter(database).commit(bookId, "t", parts())
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        // Serialized by the DB: exactly one insert; every other outcome reports AlreadyPresent.
        assertEquals(
            "two concurrent commits must produce exactly one insert + one already-present",
            1,
            outcomes.count { it is CommitResult.Committed },
        )
        assertEquals(1, outcomes.count { it is CommitResult.AlreadyPresent })
        assertEquals(1, runBlocking { database.libraryBookDao().book(BookId(bookId)) != null }.let { if (it) 1 else 0 })
        assertEquals(2, runBlocking { database.mediaPartDao().parts(BookId(bookId)).size })
    }

    @Test
    fun verifyRejectsMissingOrMismatchedIdentitySets() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            assertTrue(committer.commit(bookId, "t", parts()) is CommitResult.Committed)
            assertFalse(
                "verify must reject a wrong title",
                committer.verify(bookId, "wrong", parts()),
            )
            assertFalse(
                "verify must reject missing parts",
                committer.verify(bookId, "t", parts().dropLast(1)),
            )
            assertFalse(
                "verify must reject unknown book",
                committer.verify(UUID.randomUUID().toString(), "t", parts()),
            )
        }
}
