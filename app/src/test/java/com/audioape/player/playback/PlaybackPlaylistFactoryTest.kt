package com.audioape.player.playback

import com.audioape.core.model.BookId
import com.audioape.core.model.ContentReference
import com.audioape.core.model.MediaPart
import com.audioape.core.model.MediaPartId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackPlaylistFactoryTest {
    private val bookId = BookId("11111111-1111-1111-1111-111111111111")

    @Test
    fun constructsMediaItemsInExplicitPartOrder() {
        val playlist =
            PlaybackPlaylistFactory.create(
                "Fixture Book",
                listOf(part(1, order = 1, durationMs = 20_000L), part(0, order = 0, durationMs = 10_000L)),
            )

        assertEquals(listOf(10_000L, 20_000L), playlist.timeline.durationsMilliseconds)
        assertEquals(
            "content://fixture/0",
            playlist.mediaItems[0]
                .localConfiguration
                ?.uri
                .toString(),
        )
        assertEquals(
            "content://fixture/1",
            playlist.mediaItems[1]
                .localConfiguration
                ?.uri
                .toString(),
        )
        assertEquals("Fixture Book", playlist.mediaItems[0].mediaMetadata.title)
        assertEquals("Part 1 of 2", playlist.mediaItems[0].mediaMetadata.subtitle)
    }

    @Test
    fun metadataRoundTripsBookAndTimelineForServiceRestore() {
        val playlist =
            PlaybackPlaylistFactory.create(
                "Fixture Book",
                listOf(part(0, order = 0, durationMs = 10_000L), part(1, order = 1, durationMs = null)),
            )

        val decoded = requireNotNull(PlaybackMetadata.decode(playlist.mediaItems))

        assertEquals(bookId, decoded.bookId)
        assertEquals(listOf(10_000L, null), decoded.timeline.durationsMilliseconds)
    }

    private fun part(
        idSuffix: Int,
        order: Int,
        durationMs: Long?,
    ) = MediaPart(
        id = MediaPartId("22222222-2222-2222-2222-${idSuffix.toString().padStart(12, '0')}"),
        bookId = bookId,
        orderIndex = order,
        contentReference = ContentReference("content://fixture/$idSuffix"),
        cachedDurationMilliseconds = durationMs,
        bytes = null,
        sha256 = null,
        formatHint = null,
    )
}
