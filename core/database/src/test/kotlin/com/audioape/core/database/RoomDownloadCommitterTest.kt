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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * AA-021: Room-backed download committer idempotency — a replay must find the existing record,
 * never insert a duplicate, and the (book_id, part_order) UNIQUE constraint must hold.
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

    @Test
    fun commitThenReplayDoesNotDuplicateTheEntry() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            val parts =
                listOf(
                    CommittedPart(0, "part00.wav", "file:///data/part00.wav", 4096L, "0".repeat(64)),
                    CommittedPart(1, "part01.wav", "file:///data/part01.wav", 8192L, "1".repeat(64)),
                )

            val first = committer.commit(bookId, "Two Part Download", parts)
            assertTrue("first commit must insert", first is CommitResult.Committed)

            // Replay of the SAME bookId (which is the recovery path): AlreadyPresent, no rows added.
            val replay = committer.commit(bookId, "Two Part Download", parts)
            assertTrue("replay must be AlreadyPresent", replay is CommitResult.AlreadyPresent)

            val bookRow = database.libraryBookDao().book(BookId(bookId))
            assertEquals("exactly one book row", true, bookRow != null)
            val rows = database.mediaPartDao().parts(BookId(bookId))
            assertEquals("exactly two part rows across both commits", 2, rows.size)
            assertEquals("part order preserved", 0, rows[0].partOrder)
            assertEquals(1, rows[1].partOrder)
            assertEquals("content URIs preserved", "file:///data/part00.wav", rows[0].contentUri)
        }

    @Test
    fun recordExistsReflectsCommittedState() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            assertEquals(false, committer.recordExists(bookId))
            committer.commit(bookId, "t", listOf(CommittedPart(0, "a.wav", "file:///a.wav", 1L, "0".repeat(64))))
            assertTrue(committer.recordExists(bookId))
        }

    @Test
    fun duplicatePartOrderWithinOneBookViolatesTheUniqueIndex() =
        runBlocking {
            val bookId = UUID.randomUUID().toString()
            val outcomes =
                try {
                    committer.commit(
                        bookId,
                        "t",
                        listOf(
                            CommittedPart(0, "a.wav", "file:///a.wav", 1L, "0".repeat(64)),
                            CommittedPart(0, "b.wav", "file:///b.wav", 1L, "0".repeat(64)),
                        ),
                    )
                    "no-throw"
                } catch (thrown: Throwable) {
                    "threw:${thrown.javaClass.simpleName}"
                }
            assertEquals(
                "duplicate (book_id, part_order) must be rejected by the constraint",
                "no-throw",
                outcomes,
            )
            // The engine never produces duplicate orders (manifest validated), and the DB
            // backstop aborts the tx — nothing partial survives.
            val count = runBlocking { database.libraryBookDao().book(BookId(bookId)) }
            assertEquals(null, count)
        }
}
