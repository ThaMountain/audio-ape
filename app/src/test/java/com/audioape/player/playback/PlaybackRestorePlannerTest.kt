package com.audioape.player.playback

import com.audioape.core.database.PlaybackCheckpointEntity
import com.audioape.core.model.BookId
import com.audioape.core.model.BookTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class PlaybackRestorePlannerTest {
    private val bookId = BookId("11111111-1111-1111-1111-111111111111")

    @Test
    fun mapsBookCheckpointToPartAndAlwaysRestoresPaused() {
        val checkpoint =
            PlaybackCheckpointEntity(
                bookId = bookId,
                positionMs = 12_500L,
                lastPlayedAt = Instant.EPOCH,
                speed = 1.0f,
                lastPlayingIntent = true,
            )

        val restored =
            PlaybackRestorePlanner.restore(
                bookId,
                BookTimeline.fromDurations(listOf(10_000L, 20_000L)),
                checkpoint,
            )

        assertEquals(1, restored.mediaItemIndex)
        assertEquals(2_500L, restored.positionMs)
        assertEquals(1.0f, restored.speed)
        assertFalse(restored.playWhenReady)
    }

    @Test
    fun clampsCheckpointBeyondBookEndToFinalPartEndPaused() {
        val checkpoint =
            PlaybackCheckpointEntity(
                bookId = bookId,
                positionMs = 99_000L,
                lastPlayedAt = Instant.EPOCH,
                speed = 1.0f,
            )

        val restored =
            PlaybackRestorePlanner.restore(
                bookId,
                BookTimeline.fromDurations(listOf(10_000L, 20_000L)),
                checkpoint,
            )

        assertEquals(1, restored.mediaItemIndex)
        assertEquals(20_000L, restored.positionMs)
        assertEquals(1.0f, restored.speed)
        assertFalse(restored.playWhenReady)
    }

    @Test
    fun unknownDurationFallsBackToStartRatherThanGuessing() {
        val checkpoint =
            PlaybackCheckpointEntity(
                bookId = bookId,
                positionMs = 12_500L,
                lastPlayedAt = Instant.EPOCH,
                speed = 1.0f,
            )

        val restored =
            PlaybackRestorePlanner.restore(
                bookId,
                BookTimeline.fromDurations(listOf(10_000L, null)),
                checkpoint,
            )

        assertEquals(0, restored.mediaItemIndex)
        assertEquals(0L, restored.positionMs)
        assertFalse(restored.playWhenReady)
    }

    @Test
    fun restoresSavedPerBookSpeedWithoutRestoringPlayIntent() {
        val checkpoint =
            PlaybackCheckpointEntity(
                bookId = bookId,
                positionMs = 1_000L,
                lastPlayedAt = Instant.EPOCH,
                speed = 1.75f,
                lastPlayingIntent = true,
            )

        val restored =
            PlaybackRestorePlanner.restore(
                bookId,
                BookTimeline.fromDurations(listOf(10_000L)),
                checkpoint,
            )

        assertEquals(1.75f, restored.speed)
        assertFalse(restored.playWhenReady)
    }
}
