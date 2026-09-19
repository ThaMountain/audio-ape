package com.audioape.player.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.audioape.core.database.AudioApeDatabase
import com.audioape.core.database.LibraryBookEntity
import com.audioape.core.database.MediaPartEntity
import com.audioape.core.database.PlaybackCheckpointEntity
import com.audioape.core.model.BookId
import com.audioape.core.model.BookTimeline
import com.audioape.core.model.MediaFormatHint
import com.audioape.core.model.MediaPartId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TwoPartBookPlaybackE2ETest {
    private lateinit var context: Context
    private lateinit var database: AudioApeDatabase
    private lateinit var fixtureDirectory: File
    private var controller: MediaController? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixtureDirectory = File(context.filesDir, "aa014-e2e-${System.nanoTime()}").apply { mkdirs() }
        val partOne = copyAssetToPrivateFiles(PART_ONE_ASSET)
        val partTwo = copyAssetToPrivateFiles(PART_TWO_ASSET)

        database =
            Room
                .inMemoryDatabaseBuilder(context, AudioApeDatabase::class.java)
                .build()
        runBlocking { seedBook(partOne, partTwo) }
        PlaybackServiceDependencies.databaseFactory = { database }
    }

    @After
    fun tearDown() {
        controller?.let { connected -> onMain { connected.release() } }
        controller = null
        context.stopService(Intent(context, AudioApePlaybackService::class.java))
        if (this::database.isInitialized) {
            waitUntil(timeoutMs = 2_000L) { !database.isOpen }
        }
        PlaybackServiceDependencies.resetDatabaseFactory()
        if (this::database.isInitialized && database.isOpen) database.close()
        if (this::fixtureDirectory.isInitialized) fixtureDirectory.deleteRecursively()
    }

    @Test
    fun seededTwoPartBookRestoresPausedPlaysAndTransitionsInBookTime() {
        val connected = connectController()
        controller = connected
        assertTrue(onMain { connected.isConnected })

        assertTrue(
            "service did not restore and prepare the seeded two-part playlist",
            waitUntil(timeoutMs = 10_000L) {
                onMain {
                    connected.mediaItemCount == 2 && connected.playbackState == Player.STATE_READY
                }
            },
        )

        val uris =
            onMain {
                List(connected.mediaItemCount) { index ->
                    connected
                        .getMediaItemAt(index)
                        .localConfiguration
                        ?.uri
                        ?.lastPathSegment
                }
            }
        assertEquals(listOf(PART_ONE_ASSET, PART_TWO_ASSET), uris)
        assertFalse(onMain { connected.playWhenReady })
        assertFalse(onMain { connected.isPlaying })
        assertEquals(0, onMain { connected.currentMediaItemIndex })
        assertWithin(expected = CHECKPOINT_MS, actual = onMain { connected.currentPosition }, tolerance = 50L)
        assertWithin(expected = PART_ONE_DURATION_MS, actual = onMain { connected.duration }, tolerance = 20L)

        val positionBeforePlay = onMain { connected.currentPosition }
        onMain { connected.play() }
        assertTrue(
            "explicit play did not advance the restored paused position",
            waitUntil(timeoutMs = 5_000L) {
                onMain { connected.isPlaying && connected.currentPosition >= positionBeforePlay + 150L }
            },
        )
        onMain { connected.pause() }

        val timeline = BookTimeline.fromDurations(listOf(PART_ONE_DURATION_MS, PART_TWO_DURATION_MS))
        assertEquals(listOf(0L, PART_ONE_DURATION_MS), timeline.partStartsMilliseconds)
        assertEquals(TOTAL_DURATION_MS, timeline.totalDurationMilliseconds)
        val mapped = requireNotNull(timeline.toPartPosition(3_500L))
        assertEquals(1, mapped.partIndex)
        assertEquals(500L, mapped.localOffsetMilliseconds)
        assertEquals(3_500L, timeline.toBookPosition(1, 500L))

        onMain {
            connected.seekTo(0, 2_750L)
            connected.play()
        }
        assertTrue(
            "Media3 did not advance naturally to part 2 without another play command",
            waitUntil(timeoutMs = 5_000L) {
                onMain { connected.currentMediaItemIndex == 1 && connected.playWhenReady }
            },
        )
        onMain { connected.pause() }
        assertWithin(expected = PART_TWO_DURATION_MS, actual = onMain { connected.duration }, tolerance = 20L)

        onMain { connected.seekTo(0, 0L) }
        assertTrue(
            "controller did not acknowledge reset to part 1",
            waitUntil(timeoutMs = 2_000L) { onMain { connected.currentMediaItemIndex == 0 } },
        )
        onMain { connected.seekToNextMediaItem() }
        assertTrue(
            "skip did not select the second media item",
            waitUntil(timeoutMs = 2_000L) { onMain { connected.currentMediaItemIndex == 1 } },
        )
        assertWithin(expected = 0L, actual = onMain { connected.currentPosition }, tolerance = 100L)
        assertFalse(onMain { connected.playWhenReady })
    }

    private fun connectController(): MediaController {
        val token = SessionToken(context, ComponentName(context, AudioApePlaybackService::class.java))
        val future = onMain { MediaController.Builder(context, token).buildAsync() }
        return future.get(10L, TimeUnit.SECONDS)
    }

    private suspend fun seedBook(
        partOne: File,
        partTwo: File,
    ) {
        val importedAt = Instant.parse("2026-09-19T12:00:00Z")
        database.libraryBookDao().insertBook(
            LibraryBookEntity(
                bookId = BOOK_ID,
                editionId = null,
                displayTitle = "AA-014 two-part fixture",
                importedAt = importedAt,
                lastListenedAt = importedAt,
                finishedAt = null,
                userOwned = true,
            ),
        )
        database.mediaPartDao().insertParts(
            listOf(
                mediaPart(
                    id = PART_ONE_ID,
                    order = 0,
                    file = partOne,
                    durationMs = PART_ONE_DURATION_MS,
                ),
                mediaPart(
                    id = PART_TWO_ID,
                    order = 1,
                    file = partTwo,
                    durationMs = PART_TWO_DURATION_MS,
                ),
            ),
        )
        database.playbackCheckpointDao().saveCheckpoints(
            listOf(
                PlaybackCheckpointEntity(
                    bookId = BOOK_ID,
                    positionMs = CHECKPOINT_MS,
                    lastPlayedAt = importedAt,
                    speed = 1.0f,
                    lastPlayingIntent = true,
                ),
            ),
        )
    }

    private fun mediaPart(
        id: MediaPartId,
        order: Int,
        file: File,
        durationMs: Long,
    ) = MediaPartEntity(
        partId = id,
        bookId = BOOK_ID,
        partOrder = order,
        contentUri = Uri.fromFile(file).toString(),
        cachedDurationMs = durationMs,
        bytes = file.length(),
        sha256 = null,
        formatHint = MediaFormatHint(container = "wav", codec = "pcm_s16le"),
    )

    private fun copyAssetToPrivateFiles(name: String): File {
        val destination = File(fixtureDirectory, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50L)
        }
        return condition()
    }

    private fun assertWithin(
        expected: Long,
        actual: Long,
        tolerance: Long,
    ) {
        assertTrue("expected $actual to be within $tolerance ms of $expected", actual in expected - tolerance..expected + tolerance)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(runCatching(block))
        }
        return result.get().getOrThrow()
    }

    private companion object {
        val BOOK_ID = BookId("11111111-1111-1111-1111-111111111114")
        val PART_ONE_ID = MediaPartId("22222222-2222-2222-2222-222222222221")
        val PART_TWO_ID = MediaPartId("22222222-2222-2222-2222-222222222222")
        const val PART_ONE_ASSET = "aa014_01b_tone_a.wav"
        const val PART_TWO_ASSET = "aa014_01a_tone_b.wav"
        const val PART_ONE_DURATION_MS = 3_000L
        const val PART_TWO_DURATION_MS = 4_000L
        const val TOTAL_DURATION_MS = 7_000L
        const val CHECKPOINT_MS = 1_000L
    }
}
