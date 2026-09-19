package com.audioape.player.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audioape.core.model.BookId
import com.audioape.core.model.BookTimeline
import com.audioape.core.model.MediaPart

/** The compact, ordered Media3 representation of one logical audiobook. */
data class PlaybackPlaylist(
    val bookId: BookId,
    val timeline: BookTimeline,
    val mediaItems: List<MediaItem>,
)

object PlaybackPlaylistFactory {
    fun create(
        bookTitle: String,
        parts: List<MediaPart>,
    ): PlaybackPlaylist {
        require(bookTitle.isNotBlank()) { "book title must not be blank" }
        require(parts.isNotEmpty()) { "a playback playlist needs at least one media part" }

        val orderedParts = parts.sortedBy(MediaPart::orderIndex)
        val timeline = BookTimeline.fromParts(orderedParts)
        val bookId = orderedParts.first().bookId
        val itemCount = orderedParts.size
        val mediaItems =
            orderedParts.mapIndexed { index, part ->
                MediaItem
                    .Builder()
                    .setMediaId("${bookId.value}/${part.id.value}")
                    .setUri(part.contentReference.value.toUri())
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(bookTitle)
                            .setSubtitle("Part ${index + 1} of $itemCount")
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setExtras(
                                Bundle().apply {
                                    putString(PlaybackMetadata.BOOK_ID, bookId.value)
                                    putLong(
                                        PlaybackMetadata.PART_DURATION_MS,
                                        part.cachedDurationMilliseconds ?: UNKNOWN_DURATION,
                                    )
                                },
                            ).build(),
                    ).build()
            }

        return PlaybackPlaylist(bookId, timeline, mediaItems)
    }
}

internal object PlaybackMetadata {
    const val BOOK_ID = "com.audioape.player.playback.BOOK_ID"
    const val PART_DURATION_MS = "com.audioape.player.playback.PART_DURATION_MS"

    fun decode(mediaItems: List<MediaItem>): DecodedPlaylist? {
        if (mediaItems.isEmpty()) return null
        val bookIds = mediaItems.map { it.mediaMetadata.extras?.getString(BOOK_ID) }
        val bookId = bookIds.firstOrNull() ?: return null
        if (bookIds.any { it != bookId }) return null

        val durations =
            mediaItems.map { item ->
                val duration =
                    item.mediaMetadata.extras?.getLong(PART_DURATION_MS, UNKNOWN_DURATION)
                        ?: UNKNOWN_DURATION
                duration.takeUnless { it == UNKNOWN_DURATION }
            }
        return runCatching {
            DecodedPlaylist(BookId(bookId), BookTimeline.fromDurations(durations))
        }.getOrNull()
    }
}

internal data class DecodedPlaylist(
    val bookId: BookId,
    val timeline: BookTimeline,
)

private const val UNKNOWN_DURATION = -1L
