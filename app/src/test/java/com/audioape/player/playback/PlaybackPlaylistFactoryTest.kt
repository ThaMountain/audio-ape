package com.audioape.player.playback

import com.audioape.core.database.MediaPartEntity
import com.audioape.core.model.BookId
import com.audioape.core.model.ContentReference
import com.audioape.core.model.MediaPart
import com.audioape.core.model.MediaPartId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun entityPlaylistUsesExplicitOrderAndNeverLexicographicFilenameOrder() {
        val lexicographicallyFirst = entityPart(idSuffix = 1, filename = "01a.wav", order = 1)
        val explicitFirst = entityPart(idSuffix = 2, filename = "01b.wav", order = 0)

        val playlist =
            PlaybackPlaylistFactory.createFromEntities(
                bookTitle = "Fixture Book",
                parts = listOf(lexicographicallyFirst, explicitFirst),
            )

        assertEquals(
            listOf("content://fixture/01b.wav", "content://fixture/01a.wav"),
            playlist.mediaItems.map { it.localConfiguration?.uri.toString() },
        )
    }

    @Test
    fun completeReliableTrackMetadataWinsOverPersistedExplicitOrder() {
        val persistedFirst = entityPart(idSuffix = 3, filename = "z.wav", order = 0)
        val metadataFirst = entityPart(idSuffix = 4, filename = "a.wav", order = 1)

        val playlist =
            PlaybackPlaylistFactory.createFromEntities(
                bookTitle = "Fixture Book",
                parts = listOf(persistedFirst, metadataFirst),
                reliableTrackNumbers =
                    mapOf(
                        persistedFirst.partId to 2,
                        metadataFirst.partId to 1,
                    ),
            )

        assertEquals(
            listOf("content://fixture/a.wav", "content://fixture/z.wav"),
            playlist.mediaItems.map { it.localConfiguration?.uri.toString() },
        )
    }

    @Test
    fun partialTrackMetadataIsRejectedRatherThanMixedWithAnotherOrderingSource() {
        val first = entityPart(idSuffix = 5, filename = "01b.wav", order = 0)
        val second = entityPart(idSuffix = 6, filename = "01a.wav", order = 1)

        assertThrows(IllegalArgumentException::class.java) {
            PlaybackPlaylistFactory.createFromEntities(
                bookTitle = "Fixture Book",
                parts = listOf(first, second),
                reliableTrackNumbers = mapOf(first.partId to 1),
            )
        }
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

    private fun entityPart(
        idSuffix: Int,
        filename: String,
        order: Int,
    ) = MediaPartEntity(
        partId = MediaPartId("33333333-3333-3333-3333-${idSuffix.toString().padStart(12, '0')}"),
        bookId = bookId,
        partOrder = order,
        contentUri = "content://fixture/$filename",
        cachedDurationMs = if (order == 0) 3_000L else 4_000L,
        bytes = null,
        sha256 = null,
        formatHint = null,
    )
}
