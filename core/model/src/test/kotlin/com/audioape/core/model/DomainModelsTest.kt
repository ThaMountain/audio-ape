package com.audioape.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DomainModelsTest {
    @Test
    fun `ids require canonical UUID strings`() {
        assertEquals(WORK_ID, WorkId(WORK_ID.value))
        assertThrows(IllegalArgumentException::class.java) { WorkId("not-a-uuid") }
        assertThrows(IllegalArgumentException::class.java) {
            WorkId(WORK_ID.value.uppercase())
        }
    }

    @Test
    fun `external aliases are tuple unique`() {
        val alias = ExternalAlias("librivox", "catalog-id", "123")
        assertEquals(alias, alias.copy())
        assertNotEquals(alias, alias.copy(type = "archive-id"))

        assertThrows(IllegalArgumentException::class.java) {
            edition(
                id = EDITION_ONE_ID,
                narrator = "Narrator One",
                aliases = listOf(alias, alias.copy()),
            )
        }
    }

    @Test
    fun `same work supports distinct narrator editions`() {
        val work =
            Work(
                id = WORK_ID,
                title = "A Shared Work",
                authors = listOf("Example Author"),
            )
        val first = edition(EDITION_ONE_ID, "Narrator One")
        val second = edition(EDITION_TWO_ID, "Narrator Two")

        assertEquals(work.id, first.workId)
        assertEquals(work.id, second.workId)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.narrator, second.narrator)
    }

    @Test
    fun `confirmed edition requires audio evidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            edition(EDITION_ONE_ID, "Narrator One").copy(confirmedAudioEvidence = " ")
        }
    }

    @Test
    fun `media content references stay opaque and part order is unique per book`() {
        val rawReference = "content://provider/tree/A%2FB/../C"
        val part =
            MediaPart(
                id = MediaPartId("00000000-0000-4000-8000-000000000007"),
                bookId = BOOK_ID,
                orderIndex = 0,
                contentReference = ContentReference(rawReference),
                cachedDurationMilliseconds = 1_000L,
                bytes = 128L,
                sha256 = "a".repeat(64),
                formatHint = MediaFormatHint(container = "m4b", codec = "aac"),
            )

        assertEquals(rawReference, part.contentReference.value)
        assertThrows(IllegalArgumentException::class.java) {
            ContentReference(" ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUniqueMediaPartOrder(
                listOf(
                    part,
                    part.copy(id = MediaPartId("00000000-0000-4000-8000-000000000008")),
                ),
            )
        }
    }

    @Test
    fun `checkpoint validates speed and restores paused by default`() {
        val checkpoint =
            PlaybackCheckpoint(
                bookId = BOOK_ID,
                positionMs = 42L,
                lastPlayedAt = Instant.parse("2026-09-19T12:00:00Z"),
                speed = 1.25f,
            )

        assertFalse(checkpoint.lastPlayingIntent)
        listOf(0.49f, 3.01f, Float.NaN, Float.POSITIVE_INFINITY).forEach { speed ->
            assertThrows(IllegalArgumentException::class.java) {
                checkpoint.copy(speed = speed)
            }
        }
        checkpoint.copy(speed = 0.5f)
        checkpoint.copy(speed = 3.0f)
    }

    @Test
    fun `chapter validates individual and increasing spans`() {
        val first = chapter(CHAPTER_ONE_ID, startMs = 0L, endMs = 1_000L)
        val second = chapter(CHAPTER_TWO_ID, startMs = 1_000L, endMs = 2_000L)
        validateIncreasingChapterSpans(listOf(first, second))

        assertThrows(IllegalArgumentException::class.java) {
            chapter(CHAPTER_ONE_ID, startMs = 100L, endMs = 100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateIncreasingChapterSpans(
                listOf(first, second.copy(startMs = 900L, endMs = 1_500L)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateIncreasingChapterSpans(
                listOf(first.copy(endMs = null), second),
            )
        }
    }

    private fun edition(
        id: EditionId,
        narrator: String,
        aliases: List<ExternalAlias> = emptyList(),
    ) = AudioEdition(
        id = id,
        workId = WORK_ID,
        narrator = narrator,
        language = "en",
        durationMilliseconds = 10_000L,
        abridged = false,
        publisher = "Example Publisher",
        publicationDate = LocalDate.parse("2026-01-01"),
        externalAliases = aliases,
        confirmedAudioEvidence = "provider confirms downloadable audio tracks",
    )

    private fun chapter(
        id: ChapterId,
        startMs: Long,
        endMs: Long?,
    ) = Chapter(
        id = id,
        bookId = BOOK_ID,
        startMs = startMs,
        endMs = endMs,
        title = null,
        provenance = "embedded chapter metadata",
        acceptedOnline = false,
    )

    private companion object {
        val WORK_ID = WorkId("123e4567-e89b-42d3-a456-426614174001")
        val EDITION_ONE_ID = EditionId("00000000-0000-4000-8000-000000000002")
        val EDITION_TWO_ID = EditionId("00000000-0000-4000-8000-000000000003")
        val BOOK_ID = BookId("00000000-0000-4000-8000-000000000004")
        val CHAPTER_ONE_ID = ChapterId("00000000-0000-4000-8000-000000000005")
        val CHAPTER_TWO_ID = ChapterId("00000000-0000-4000-8000-000000000006")
    }
}
