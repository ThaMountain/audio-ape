package com.audioape.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.audioape.core.model.AudioEdition
import com.audioape.core.model.BookId
import com.audioape.core.model.Bookmark
import com.audioape.core.model.BookmarkId
import com.audioape.core.model.Chapter
import com.audioape.core.model.ChapterId
import com.audioape.core.model.ContentReference
import com.audioape.core.model.EditionId
import com.audioape.core.model.MediaFormatHint
import com.audioape.core.model.MediaPart
import com.audioape.core.model.MediaPartId
import com.audioape.core.model.PlaybackCheckpoint
import com.audioape.core.model.SeriesReference
import com.audioape.core.model.Work
import com.audioape.core.model.WorkId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity(tableName = "work")
data class WorkEntity(
    @PrimaryKey
    @ColumnInfo(name = "work_id")
    val workId: WorkId,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "authors")
    val authors: List<String>,
    @ColumnInfo(name = "series_reference")
    val seriesReference: String? = null,
    @ColumnInfo(name = "series_order")
    val seriesOrder: Double? = null,
) {
    init {
        Work(
            id = workId,
            title = title,
            authors = authors,
            series = seriesReference?.let { SeriesReference(it, seriesOrder) },
        )
        require(seriesReference != null || seriesOrder == null) {
            "series order requires a series reference"
        }
    }
}

@Entity(
    tableName = "audio_edition",
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["work_id"],
            childColumns = ["work_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["work_id"])],
)
data class AudioEditionEntity(
    @PrimaryKey
    @ColumnInfo(name = "edition_id")
    val editionId: EditionId,
    @ColumnInfo(name = "work_id")
    val workId: WorkId,
    @ColumnInfo(name = "narrator")
    val narrator: String?,
    @ColumnInfo(name = "language")
    val language: String?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "abridged")
    val abridged: Boolean?,
    @ColumnInfo(name = "publisher")
    val publisher: String?,
    @ColumnInfo(name = "publication_date")
    val publicationDate: LocalDate?,
    @ColumnInfo(name = "confirmed_audio_evidence")
    val confirmedAudioEvidence: String,
) {
    init {
        AudioEdition(
            id = editionId,
            workId = workId,
            narrator = narrator,
            language = language,
            durationMilliseconds = durationMs,
            abridged = abridged,
            publisher = publisher,
            publicationDate = publicationDate,
            externalAliases = emptyList(),
            confirmedAudioEvidence = confirmedAudioEvidence,
        )
    }
}

@Entity(
    tableName = "external_alias",
    primaryKeys = ["provider", "alias_type", "value"],
    foreignKeys = [
        ForeignKey(
            entity = AudioEditionEntity::class,
            parentColumns = ["edition_id"],
            childColumns = ["edition_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["edition_id"])],
)
data class ExternalAliasEntity(
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "alias_type")
    val type: String,
    @ColumnInfo(name = "value")
    val value: String,
    @ColumnInfo(name = "edition_id")
    val editionId: EditionId,
) {
    init {
        com.audioape.core.model
            .ExternalAlias(provider, type, value)
    }
}

@Entity(
    tableName = "library_book",
    foreignKeys = [
        ForeignKey(
            entity = AudioEditionEntity::class,
            parentColumns = ["edition_id"],
            childColumns = ["edition_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["edition_id"]), Index(value = ["last_listened_at"])],
)
data class LibraryBookEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: BookId,
    @ColumnInfo(name = "edition_id")
    val editionId: EditionId?,
    @ColumnInfo(name = "display_title")
    val displayTitle: String,
    @ColumnInfo(name = "imported_at")
    val importedAt: Instant,
    @ColumnInfo(name = "last_listened_at")
    val lastListenedAt: Instant?,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Instant?,
    @ColumnInfo(name = "user_owned")
    val userOwned: Boolean,
) {
    init {
        com.audioape.core.model.LibraryBook(
            id = bookId,
            editionId = editionId,
            displayTitle = displayTitle,
            importedAt = importedAt,
            lastListenedAt = lastListenedAt,
            finishedAt = finishedAt,
            userOwned = userOwned,
        )
    }
}

@Entity(
    tableName = "media_part",
    foreignKeys = [
        ForeignKey(
            entity = LibraryBookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_id", "part_order"], unique = true),
        Index(value = ["sha256"]),
    ],
)
data class MediaPartEntity(
    @PrimaryKey
    @ColumnInfo(name = "part_id")
    val partId: MediaPartId,
    @ColumnInfo(name = "book_id")
    val bookId: BookId,
    @ColumnInfo(name = "part_order")
    val partOrder: Int,
    @ColumnInfo(name = "content_uri")
    val contentUri: String,
    @ColumnInfo(name = "cached_duration_ms")
    val cachedDurationMs: Long?,
    @ColumnInfo(name = "bytes")
    val bytes: Long?,
    @ColumnInfo(name = "sha256")
    val sha256: String?,
    @ColumnInfo(name = "codec_container")
    val formatHint: MediaFormatHint?,
) {
    init {
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
    }
}

@Entity(
    tableName = "chapter",
    foreignKeys = [
        ForeignKey(
            entity = LibraryBookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id", "start_ms"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey
    @ColumnInfo(name = "chapter_id")
    val chapterId: ChapterId,
    @ColumnInfo(name = "book_id")
    val bookId: BookId,
    @ColumnInfo(name = "start_ms")
    val startMs: Long,
    @ColumnInfo(name = "end_ms")
    val endMs: Long?,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "provenance")
    val provenance: String,
    @ColumnInfo(name = "accepted_online")
    val acceptedOnline: Boolean,
) {
    init {
        toModel()
    }

    internal fun toModel(): Chapter =
        Chapter(
            id = chapterId,
            bookId = bookId,
            startMs = startMs,
            endMs = endMs,
            title = title,
            provenance = provenance,
            acceptedOnline = acceptedOnline,
        )
}

@Entity(
    tableName = "playback_checkpoint",
    foreignKeys = [
        ForeignKey(
            entity = LibraryBookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaybackCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: BookId,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Instant,
    @ColumnInfo(name = "speed")
    val speed: Float,
    @ColumnInfo(name = "last_playing_intent")
    val lastPlayingIntent: Boolean = false,
) {
    init {
        PlaybackCheckpoint(bookId, positionMs, lastPlayedAt, speed, lastPlayingIntent)
    }
}

@Entity(
    tableName = "bookmark",
    foreignKeys = [
        ForeignKey(
            entity = LibraryBookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id", "position_ms"])],
)
data class BookmarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: BookmarkId,
    @ColumnInfo(name = "book_id")
    val bookId: BookId,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "label")
    val label: String?,
    @ColumnInfo(name = "note")
    val note: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
) {
    init {
        Bookmark(id, bookId, positionMs, label, note, createdAt)
    }
}

@Entity(
    tableName = "collection",
    indices = [Index(value = ["name"], unique = true)],
)
data class CollectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "collection_id")
    val collectionId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
) {
    init {
        requireCanonicalUuid(collectionId, "collection id")
        require(name.isNotBlank()) { "collection name must not be blank" }
    }
}

@Entity(
    tableName = "collection_item",
    primaryKeys = ["collection_id", "book_id"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["collection_id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LibraryBookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id"])],
)
data class CollectionItemEntity(
    @ColumnInfo(name = "collection_id")
    val collectionId: String,
    @ColumnInfo(name = "book_id")
    val bookId: BookId,
    @ColumnInfo(name = "added_at")
    val addedAt: Instant,
) {
    init {
        requireCanonicalUuid(collectionId, "collection id")
    }
}

/**
 * Minimal app-private listening history. It intentionally has no URI, path, media bytes, notes,
 * bookmarks, or other field that could become an external media sidecar.
 */
@Entity(
    tableName = "book_history_tombstone",
    indices = [Index(value = ["alias_provider", "alias_type", "alias_value"], unique = true)],
)
data class BookHistoryTombstoneEntity(
    @PrimaryKey
    @ColumnInfo(name = "tombstone_id")
    val tombstoneId: String,
    @ColumnInfo(name = "alias_provider")
    val aliasProvider: String,
    @ColumnInfo(name = "alias_type")
    val aliasType: String,
    @ColumnInfo(name = "alias_value")
    val aliasValue: String,
    @ColumnInfo(name = "finished")
    val finished: Boolean,
    @ColumnInfo(name = "deleted")
    val deleted: Boolean,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Instant?,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant?,
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Instant,
) {
    init {
        requireCanonicalUuid(tombstoneId, "tombstone id")
        com.audioape.core.model
            .ExternalAlias(aliasProvider, aliasType, aliasValue)
        require(finished || deleted) { "a history tombstone must record finished or deleted state" }
        require(finished == (finishedAt != null)) { "finished flag and timestamp must agree" }
        require(deleted == (deletedAt != null)) { "deleted flag and timestamp must agree" }
    }
}

/**
 * External content for local search. Room-generated triggers keep [LibrarySearchFtsEntity] in sync.
 * Personal listening state is deliberately absent.
 */
@Entity(
    tableName = "library_search_content",
    foreignKeys = [
        ForeignKey(
            entity = LibraryBookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id"], unique = true)],
)
data class LibrarySearchContentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    @ColumnInfo(name = "book_id")
    val bookId: BookId,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "authors")
    val authors: String,
    @ColumnInfo(name = "narrator")
    val narrator: String?,
    @ColumnInfo(name = "series")
    val series: String?,
)

/** FTS mirror of [LibrarySearchContentEntity], synchronized by Room-generated SQLite triggers. */
@Fts4(contentEntity = LibrarySearchContentEntity::class, notIndexed = ["book_id"])
@Entity(tableName = "library_search_fts")
data class LibrarySearchFtsEntity(
    @ColumnInfo(name = "book_id")
    val bookId: BookId,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "authors")
    val authors: String,
    @ColumnInfo(name = "narrator")
    val narrator: String?,
    @ColumnInfo(name = "series")
    val series: String?,
)

private fun requireCanonicalUuid(
    value: String,
    label: String,
) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value) { "$label must be a canonical UUID" }
}
