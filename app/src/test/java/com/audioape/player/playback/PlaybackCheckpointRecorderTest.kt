package com.audioape.player.playback

import com.audioape.core.database.PlaybackCheckpointEntity
import com.audioape.core.model.BookId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCheckpointRecorderTest {
    private val bookId = BookId("11111111-1111-1111-1111-111111111115")

    @Test
    fun playToPauseWritesLatestCheckpoint() =
        runTest {
            var snapshot = checkpoint(positionMs = 1_000L)
            val writes = mutableListOf<List<PlaybackCheckpointEntity>>()
            val recorder = recorder({ snapshot }, writes)

            recorder.onPlayWhenReadyChanged(true)
            snapshot = checkpoint(positionMs = 1_450L)
            recorder.onPlayWhenReadyChanged(false)
            advancePastBatchWindow()

            assertEquals(1, writes.size)
            assertEquals(1_450L, writes.single().single().positionMs)
            assertFalse(writes.single().single().lastPlayingIntent)
        }

    @Test
    fun committedSeekWritesEvenWhenPlaybackIsPaused() =
        runTest {
            val writes = mutableListOf<List<PlaybackCheckpointEntity>>()
            val recorder = recorder({ checkpoint(positionMs = 4_200L) }, writes)

            recorder.onSeekCommitted()
            advancePastBatchWindow()

            assertEquals(4_200L, writes.single().single().positionMs)
        }

    @Test
    fun activeMediaItemTransitionWritesBookCheckpoint() =
        runTest {
            val writes = mutableListOf<List<PlaybackCheckpointEntity>>()
            val recorder = recorder({ checkpoint(positionMs = 10_000L) }, writes)

            recorder.onIsPlayingChanged(true)
            recorder.onMediaItemTransition()
            advancePastBatchWindow()

            assertEquals(10_000L, writes.single().single().positionMs)
        }

    @Test
    fun activePlaybackWritesEveryTenSeconds() =
        runTest {
            var position = 2_000L
            val writes = mutableListOf<List<PlaybackCheckpointEntity>>()
            val recorder = recorder({ checkpoint(positionMs = position) }, writes)

            recorder.onIsPlayingChanged(true)
            position = 12_000L
            advanceTimeBy(PERIODIC_INTERVAL_MS)
            runCurrent()
            advancePastBatchWindow()

            assertEquals(12_000L, writes.single().single().positionMs)
        }

    @Test
    fun nearbyEventsCoalesceIntoOneLatestPerBookBatch() =
        runTest {
            var position = 5_000L
            val writes = mutableListOf<List<PlaybackCheckpointEntity>>()
            val recorder = recorder({ checkpoint(positionMs = position) }, writes)

            recorder.onIsPlayingChanged(true)
            recorder.onSeekCommitted()
            position = 5_100L
            recorder.onMediaItemTransition()
            position = 5_200L
            recorder.onPlayWhenReadyChanged(true)
            recorder.onPlayWhenReadyChanged(false)
            advancePastBatchWindow()

            assertEquals(1, writes.size)
            assertEquals(1, writes.single().size)
            assertEquals(5_200L, writes.single().single().positionMs)
        }

    @Test
    fun pausedPlaybackSuppressesPeriodicAndPassiveTransitionWrites() =
        runTest {
            val writes = mutableListOf<List<PlaybackCheckpointEntity>>()
            val recorder = recorder({ checkpoint(positionMs = 1_000L) }, writes)

            recorder.onIsPlayingChanged(false)
            recorder.onMediaItemTransition()
            advanceTimeBy(PERIODIC_INTERVAL_MS * 3)
            runCurrent()

            assertTrue(writes.isEmpty())
        }

    @Test
    fun normalShutdownReturnsFinalCheckpointForBestEffortWrite() =
        runTest {
            val writes = mutableListOf<List<PlaybackCheckpointEntity>>()
            val recorder = recorder({ checkpoint(positionMs = 6_700L, speed = 1.5f) }, writes)

            val finalBatch = recorder.stopAndTakeFinalBatch()
            val saved = recorder.writeFinalBatch(finalBatch)

            assertTrue(saved)
            assertEquals(6_700L, writes.single().single().positionMs)
            assertEquals(1.5f, writes.single().single().speed)
        }

    private fun TestScope.recorder(
        snapshot: () -> PlaybackCheckpointSnapshot?,
        writes: MutableList<List<PlaybackCheckpointEntity>>,
    ) = PlaybackCheckpointRecorder(
        scope = backgroundScope,
        checkpointProvider = snapshot,
        saveCheckpoints = { writes += it },
        now = { Instant.ofEpochMilli(testScheduler.currentTime) },
        periodicIntervalMs = PERIODIC_INTERVAL_MS,
        batchWindowMs = BATCH_WINDOW_MS,
    )

    private fun checkpoint(
        positionMs: Long,
        speed: Float = 1.0f,
    ) = PlaybackCheckpointSnapshot(bookId, positionMs, speed)

    private fun kotlinx.coroutines.test.TestScope.advancePastBatchWindow() {
        advanceTimeBy(BATCH_WINDOW_MS)
        runCurrent()
    }

    private companion object {
        const val PERIODIC_INTERVAL_MS = 10_000L
        const val BATCH_WINDOW_MS = 250L
    }
}
