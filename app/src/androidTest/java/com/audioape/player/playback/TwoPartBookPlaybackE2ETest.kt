package com.audioape.player.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import java.io.FileInputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TwoPartBookPlaybackE2ETest {
    private lateinit var context: Context
    private lateinit var database: AudioApeDatabase
    private lateinit var fixtureDirectory: File
    private lateinit var testDatabaseName: String
    private var controller: MediaController? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        killPlaybackProcessIfRunning()
        testDatabaseName = "aa015-e2e-${System.nanoTime()}.db"
        PlaybackServiceDependencies.setTestDatabaseName(context, testDatabaseName)
        context.deleteDatabase(testDatabaseName)
        fixtureDirectory = File(context.filesDir, "aa014-e2e-${System.nanoTime()}").apply { mkdirs() }
        val partOne = copyAssetToPrivateFiles(PART_ONE_ASSET)
        val partTwo = copyAssetToPrivateFiles(PART_TWO_ASSET)

        database = AudioApeDatabase.create(context, testDatabaseName)
        runBlocking { seedBook(partOne, partTwo) }
        database.close()
    }

    @After
    fun tearDown() {
        controller?.let { connected -> runCatching { onMain { connected.release() } } }
        controller = null
        context.stopService(Intent(context, AudioApePlaybackService::class.java))
        waitUntil(timeoutMs = 2_000L) { playbackPid() == null }
        killPlaybackProcessIfRunning()
        PlaybackServiceDependencies.resetDatabaseFactory()
        PlaybackServiceDependencies.setTestDatabaseName(context, null)
        if (this::database.isInitialized && database.isOpen) database.close()
        if (this::testDatabaseName.isInitialized) context.deleteDatabase(testDatabaseName)
        if (this::fixtureDirectory.isInitialized) fixtureDirectory.deleteRecursively()
    }

    @Test
    fun coldRestoreAndPlaybackProcessDeathRemainPausedAtBoundedCheckpointWithSpeed() {
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

        val mediaIds =
            onMain {
                List(connected.mediaItemCount) { index ->
                    connected.getMediaItemAt(index).mediaId
                }
            }
        assertEquals(
            listOf(
                "${BOOK_ID.value}/${PART_ONE_ID.value}",
                "${BOOK_ID.value}/${PART_TWO_ID.value}",
            ),
            mediaIds,
        )
        assertFalse(onMain { connected.playWhenReady })
        assertFalse(onMain { connected.isPlaying })
        assertEquals(0, onMain { connected.currentMediaItemIndex })
        assertWithin(expected = CHECKPOINT_MS, actual = onMain { connected.currentPosition }, tolerance = 50L)
        assertWithin(expected = PART_ONE_DURATION_MS, actual = onMain { connected.duration }, tolerance = 20L)
        assertEquals(CHECKPOINT_SPEED, onMain { connected.playbackParameters.speed }, 0.01f)

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
        assertWithin(expected = PART_TWO_DURATION_MS, actual = onMain { connected.duration }, tolerance = 20L)
        assertTrue(
            "part transition checkpoint did not get a bounded interval to flush",
            waitUntil(timeoutMs = 2_000L) { onMain { bookPosition(connected) >= 3_600L } },
        )
        val positionBeforeKill = onMain { bookPosition(connected) }

        onMain { connected.release() }
        controller = null
        killPlaybackProcess()

        val checkpointAfterKill = readCheckpoint()
        assertFalse(checkpointAfterKill.lastPlayingIntent)
        assertEquals(CHECKPOINT_SPEED, checkpointAfterKill.speed, 0.01f)
        assertTrue(
            "the active part transition checkpoint was not persisted",
            checkpointAfterKill.positionMs >= PART_ONE_DURATION_MS,
        )
        assertTrue(
            "checkpoint loss must be nonnegative and bounded after unclean process death",
            positionBeforeKill - checkpointAfterKill.positionMs in 0L..MAX_KILL_LOSS_MS,
        )

        val restored = connectController()
        controller = restored
        assertTrue(
            "service did not cold-restore after its playback process was killed",
            waitUntil(timeoutMs = 10_000L) {
                onMain {
                    restored.mediaItemCount == 2 && restored.playbackState == Player.STATE_READY
                }
            },
        )
        assertFalse(onMain { restored.playWhenReady })
        assertFalse(onMain { restored.isPlaying })
        assertEquals(CHECKPOINT_SPEED, onMain { restored.playbackParameters.speed }, 0.01f)
        assertWithin(
            expected = checkpointAfterKill.positionMs,
            actual = onMain { bookPosition(restored) },
            tolerance = 100L,
        )

        val restoredPosition = onMain { bookPosition(restored) }
        onMain { restored.play() }
        assertTrue(
            "explicit play did not advance after killed-process cold restore",
            waitUntil(timeoutMs = 5_000L) {
                onMain { restored.isPlaying && bookPosition(restored) >= restoredPosition + 150L }
            },
        )
        onMain { restored.pause() }
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
                    speed = CHECKPOINT_SPEED,
                    lastPlayingIntent = false,
                ),
            ),
        )
    }

    private fun readCheckpoint(): PlaybackCheckpointEntity {
        val reopened = AudioApeDatabase.create(context, testDatabaseName)
        return try {
            runBlocking { requireNotNull(reopened.playbackCheckpointDao().checkpoint(BOOK_ID)) }
        } finally {
            reopened.close()
        }
    }

    private fun bookPosition(connected: MediaController): Long =
        when (connected.currentMediaItemIndex) {
            0 -> connected.currentPosition
            1 -> PART_ONE_DURATION_MS + connected.currentPosition
            else -> error("unexpected media item index ${connected.currentMediaItemIndex}")
        }

    private fun killPlaybackProcess() {
        val pid = requireNotNull(playbackPid()) { "playback process was not running" }
        readShellCommand("am crash $pid")
        assertTrue(
            "playback process $pid survived the injected process crash",
            waitUntil(timeoutMs = 5_000L) { playbackPid() != pid },
        )
    }

    private fun killPlaybackProcessIfRunning() {
        playbackPid()?.let { pid ->
            readShellCommand("am crash $pid")
            waitUntil(timeoutMs = 5_000L) { playbackPid() != pid }
        }
    }

    private fun playbackPid(): Int? =
        readShellCommand("pidof $PLAYBACK_PROCESS_NAME")
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull { it.isNotEmpty() }
            ?.toIntOrNull()

    private fun readShellCommand(command: String): String {
        val descriptor: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return descriptor.use { FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() } }
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
        const val CHECKPOINT_MS = 2_750L
        const val CHECKPOINT_SPEED = 1.5f

        /**
         * Bounded-loss window = one periodic flush cadence (PLY-013: 10s interval). The durable
         * checkpoint is whatever last periodic/transition flush committed before the kill; genuine
         * loss is nonnegative and at most one cadence. Observed loss is usually far smaller
         * (sub-second) because the part-transition flush precedes the crash.
         */
        const val MAX_KILL_LOSS_MS = 10_000L
        const val PLAYBACK_PROCESS_NAME = "com.audioape.player:playback"
    }
}
