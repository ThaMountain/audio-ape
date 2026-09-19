package com.audioape.player.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audioape.core.database.MediaPartEntity
import com.audioape.core.model.BookId
import com.audioape.core.model.BookTimeline
import com.audioape.core.model.ContentReference
import com.audioape.core.model.MediaPart
import com.audioape.core.model.MediaPartId

/** The compact, ordered Media3 representation of one logical audiobook. */
data class PlaybackPlaylist(
    val bookId: BookId,
    val timeline: BookTimeline,
    val mediaItems: List<MediaItem>,
)

object PlaybackPlaylistFactory {
    /**
     * Builds a playlist directly from persisted parts. A complete set of positive, unique track
     * numbers is authoritative when supplied; otherwise persisted `partOrder` is authoritative.
     * Filenames and URI text are never ordering inputs.
     */
    fun createFromEntities(
        bookTitle: String,
        parts: List<MediaPartEntity>,
        reliableTrackNumbers: Map<MediaPartId, Int> = emptyMap(),
    ): PlaybackPlaylist {
        val ordered =
            PlaybackPartOrderResolver.resolve(
                parts = parts,
                explicitPartOrder = MediaPartEntity::partOrder,
                partId = MediaPartEntity::partId,
                reliableTrackNumbers = reliableTrackNumbers,
            )
        return createInResolvedOrder(bookTitle, ordered.map(MediaPartEntity::toPlaybackModel))
    }

    fun create(
        bookTitle: String,
        parts: List<MediaPart>,
    ): PlaybackPlaylist {
        val ordered =
            PlaybackPartOrderResolver.resolve(
                parts = parts,
                explicitPartOrder = MediaPart::orderIndex,
                partId = MediaPart::id,
                reliableTrackNumbers = emptyMap(),
            )
        return createInResolvedOrder(bookTitle, ordered)
    }

    private fun createInResolvedOrder(
        bookTitle: String,
        parts: List<MediaPart>,
    ): PlaybackPlaylist {
        require(bookTitle.isNotBlank()) { "book title must not be blank" }
        require(parts.isNotEmpty()) { "a playback playlist needs at least one media part" }

        val orderedParts = parts.mapIndexed { index, part -> part.copy(orderIndex = index) }
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

/** Resolves only trusted numeric ordering fields; URI/filename comparison is deliberately absent. */
internal object PlaybackPartOrderResolver {
    fun <T> resolve(
        parts: List<T>,
        explicitPartOrder: (T) -> Int,
        partId: (T) -> MediaPartId,
        reliableTrackNumbers: Map<MediaPartId, Int>,
    ): List<T> {
        require(parts.isNotEmpty()) { "a playback playlist needs at least one media part" }

        val explicitOrders = parts.map(explicitPartOrder)
        require(explicitOrders.all { it >= 0 }) { "explicit part order must be nonnegative" }
        require(explicitOrders.distinct().size == explicitOrders.size) {
            "explicit part order must be unique"
        }

        if (reliableTrackNumbers.isEmpty()) return parts.sortedBy(explicitPartOrder)

        val ids = parts.map(partId)
        require(ids.distinct().size == ids.size) { "media part IDs must be unique" }
        require(reliableTrackNumbers.keys == ids.toSet()) {
            "reliable track metadata must cover every and only playlist part"
        }
        val tracks = ids.map { requireNotNull(reliableTrackNumbers[it]) }
        require(tracks.all { it > 0 }) { "reliable track numbers must be positive" }
        require(tracks.distinct().size == tracks.size) { "reliable track numbers must be unique" }

        return parts.sortedBy { requireNotNull(reliableTrackNumbers[partId(it)]) }
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

private fun MediaPartEntity.toPlaybackModel(): MediaPart =
    MediaPart(
        id = partId,
        bookId = bookId,
        orderIndex = partOrder,
        contentReference = ContentReference(contentUri),
        cachedDurationMilliseconds = cachedDurationMs,
        bytes = bytes,
        sha256 = sha256,
        formatHint = formatHint,
    )
