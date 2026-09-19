package com.audioape.core.model

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate

/** A normalized reference to a series and this work's optional position in it. */
data class SeriesReference(
    val reference: String,
    val order: Double? = null,
) {
    init {
        requireNormalizedText(reference, "series reference")
        require(order == null || (order.isFinite() && order > 0.0)) {
            "series order must be finite and greater than zero"
        }
    }
}

/**
 * Written content independent of any print or audio edition.
 *
 * Author names must be nonempty, NFC-normalized, trimmed, and unique. Narrator data belongs on
 * [AudioEdition], never here.
 */
data class Work(
    val id: WorkId,
    val title: String,
    val authors: List<String>,
    val series: SeriesReference? = null,
) {
    init {
        requireNormalizedText(title, "work title")
        require(authors.isNotEmpty()) { "a work must have at least one author" }
        authors.forEach { requireNormalizedText(it, "author") }
        require(authors.distinct().size == authors.size) { "authors must be unique" }
    }
}

/** A provider-scoped identifier. Equality represents uniqueness of `(provider, type, value)`. */
data class ExternalAlias(
    val provider: String,
    val type: String,
    val value: String,
) {
    init {
        requireNonBlank(provider, "alias provider")
        requireNonBlank(type, "alias type")
        requireNonBlank(value, "alias value")
    }
}

/**
 * A specific confirmed audio rendition of a [Work].
 *
 * Duration is positive. Optional descriptive fields remain unknown rather than being guessed.
 * Evidence must be nonempty, and aliases must be tuple-unique.
 */
data class AudioEdition(
    val id: EditionId,
    val workId: WorkId,
    val narrator: String?,
    val language: String?,
    val durationMilliseconds: Long,
    val abridged: Boolean?,
    val publisher: String?,
    val publicationDate: LocalDate?,
    val externalAliases: List<ExternalAlias>,
    val confirmedAudioEvidence: String,
) {
    init {
        narrator?.let { requireNonBlank(it, "narrator") }
        language?.let { requireNonBlank(it, "language") }
        require(durationMilliseconds > 0L) { "edition duration must be greater than zero" }
        publisher?.let { requireNonBlank(it, "publisher") }
        require(externalAliases.distinct().size == externalAliases.size) {
            "external aliases must be unique by provider, type, and value"
        }
        requireNonBlank(confirmedAudioEvidence, "confirmed audio evidence")
    }
}

/**
 * One locally owned logical audiobook, distinct from both its written work and audio edition.
 * Listening metadata remains app-private and is not a media sidecar.
 */
data class LibraryBook(
    val id: BookId,
    val editionId: EditionId?,
    val displayTitle: String,
    val importedAt: Instant,
    val lastListenedAt: Instant?,
    val finishedAt: Instant?,
    val userOwned: Boolean,
) {
    init {
        requireNonBlank(displayTitle, "book display title")
    }
}

/** Opaque provider content reference, commonly a `content://` URI. It is never path-normalized. */
data class ContentReference(
    val value: String,
) {
    init {
        requireNonBlank(value, "content reference")
    }
}

/** Optional container and codec observations; these are hints, not playback guarantees. */
data class MediaFormatHint(
    val container: String? = null,
    val codec: String? = null,
) {
    init {
        require(container != null || codec != null) { "at least one media format hint is required" }
        container?.let { requireNonBlank(it, "container hint") }
        codec?.let { requireNonBlank(it, "codec hint") }
    }
}

/**
 * One ordered playable part. `(bookId, orderIndex)` must be unique within a book.
 *
 * Duration and byte count may be unknown. SHA-256, when present, is lowercase hexadecimal.
 */
data class MediaPart(
    val id: MediaPartId,
    val bookId: BookId,
    val orderIndex: Int,
    val contentReference: ContentReference,
    val cachedDurationMilliseconds: Long?,
    val bytes: Long?,
    val sha256: String?,
    val formatHint: MediaFormatHint?,
) {
    init {
        require(orderIndex >= 0) { "media part order index must be nonnegative" }
        require(cachedDurationMilliseconds == null || cachedDurationMilliseconds > 0L) {
            "known media part duration must be greater than zero"
        }
        require(bytes == null || bytes >= 0L) { "media part byte count must be nonnegative" }
        require(sha256 == null || SHA_256.matches(sha256)) {
            "SHA-256 must be 64 lowercase hexadecimal characters"
        }
    }
}

/**
 * One book-wide chapter span. A known end is strictly after its nonnegative start.
 * Use [validateIncreasingChapterSpans] to validate ordering across chapters.
 */
data class Chapter(
    val id: ChapterId,
    val bookId: BookId,
    val startMs: Long,
    val endMs: Long?,
    val title: String?,
    val provenance: String,
    val acceptedOnline: Boolean,
) {
    init {
        require(startMs >= 0L) { "chapter start must be nonnegative" }
        require(endMs == null || endMs > startMs) { "chapter end must be after its start" }
        title?.let { requireNonBlank(it, "chapter title") }
        requireNonBlank(provenance, "chapter provenance")
    }
}

/** Durable book-wide playback state. Restored checkpoints default to paused intent. */
data class PlaybackCheckpoint(
    val bookId: BookId,
    val positionMs: Long,
    val lastPlayedAt: Instant,
    val speed: Float,
    val lastPlayingIntent: Boolean = false,
) {
    init {
        require(positionMs >= 0L) { "checkpoint position must be nonnegative" }
        require(speed.isFinite() && speed in 0.5f..3.0f) {
            "playback speed must be finite and between 0.5 and 3.0"
        }
    }
}

/** User-authored app-private marker on the book-wide timeline. */
data class Bookmark(
    val id: BookmarkId,
    val bookId: BookId,
    val positionMs: Long,
    val label: String?,
    val note: String?,
    val createdAt: Instant,
) {
    init {
        require(positionMs >= 0L) { "bookmark position must be nonnegative" }
        label?.let { requireNonBlank(it, "bookmark label") }
        note?.let { requireNonBlank(it, "bookmark note") }
    }
}

/** Validates the model-level `(bookId, orderIndex)` uniqueness constraint. */
fun validateUniqueMediaPartOrder(parts: List<MediaPart>) {
    val keys = parts.map { it.bookId to it.orderIndex }
    require(keys.distinct().size == keys.size) {
        "media part order index must be unique within a book"
    }
}

/**
 * Validates same-book, start-ordered, nonoverlapping chapter spans.
 * An open-ended chapter must be last because no later nonoverlap can be established.
 */
fun validateIncreasingChapterSpans(chapters: List<Chapter>) {
    if (chapters.isEmpty()) return

    val bookId = chapters.first().bookId
    chapters.forEachIndexed { index, chapter ->
        require(chapter.bookId == bookId) { "all chapters must belong to the same book" }
        if (index > 0) {
            val previous = chapters[index - 1]
            require(chapter.startMs > previous.startMs) {
                "chapter starts must be strictly increasing"
            }
            require(previous.endMs != null && chapter.startMs >= previous.endMs) {
                "chapter spans must not overlap and an open-ended chapter must be last"
            }
        }
    }
}

private val SHA_256 = Regex("[0-9a-f]{64}")

private fun requireNonBlank(
    value: String,
    label: String,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
}

private fun requireNormalizedText(
    value: String,
    label: String,
) {
    requireNonBlank(value, label)
    require(value == value.trim() && Normalizer.normalize(value, Normalizer.Form.NFC) == value) {
        "$label must be trimmed and NFC-normalized"
    }
}
