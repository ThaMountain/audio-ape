package com.audioape.player.playback

import com.audioape.core.database.PlaybackCheckpointEntity
import com.audioape.core.model.BookId
import com.audioape.core.model.BookTimeline

data class PlaybackStart(
    val mediaItemIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
)

/** Maps a private, book-wide checkpoint to Media3 coordinates without restoring play intent. */
object PlaybackRestorePlanner {
    fun restore(
        bookId: BookId,
        timeline: BookTimeline,
        checkpoint: PlaybackCheckpointEntity?,
    ): PlaybackStart {
        require(checkpoint == null || checkpoint.bookId == bookId) {
            "checkpoint must belong to the playlist book"
        }
        val partPosition = timeline.toPartPosition(checkpoint?.positionMs ?: 0L)
        return PlaybackStart(
            mediaItemIndex = partPosition?.partIndex ?: 0,
            positionMs = partPosition?.localOffsetMilliseconds ?: 0L,
            playWhenReady = false,
        )
    }
}
