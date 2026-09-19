package com.audioape.core.database

import androidx.room.TypeConverter
import com.audioape.core.model.BookId
import com.audioape.core.model.BookmarkId
import com.audioape.core.model.ChapterId
import com.audioape.core.model.EditionId
import com.audioape.core.model.MediaFormatHint
import com.audioape.core.model.MediaPartId
import com.audioape.core.model.WorkId
import java.time.Instant
import java.time.LocalDate

internal class DatabaseConverters {
    @TypeConverter
    fun workIdToString(value: WorkId): String = value.value

    @TypeConverter
    fun stringToWorkId(value: String): WorkId = WorkId(value)

    @TypeConverter
    fun editionIdToString(value: EditionId): String = value.value

    @TypeConverter
    fun stringToEditionId(value: String): EditionId = EditionId(value)

    @TypeConverter
    fun bookIdToString(value: BookId): String = value.value

    @TypeConverter
    fun stringToBookId(value: String): BookId = BookId(value)

    @TypeConverter
    fun mediaPartIdToString(value: MediaPartId): String = value.value

    @TypeConverter
    fun stringToMediaPartId(value: String): MediaPartId = MediaPartId(value)

    @TypeConverter
    fun chapterIdToString(value: ChapterId): String = value.value

    @TypeConverter
    fun stringToChapterId(value: String): ChapterId = ChapterId(value)

    @TypeConverter
    fun bookmarkIdToString(value: BookmarkId): String = value.value

    @TypeConverter
    fun stringToBookmarkId(value: String): BookmarkId = BookmarkId(value)

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun authorsToString(value: List<String>): String {
        require(value.none { AUTHOR_DELIMITER in it }) { "author contains reserved delimiter" }
        return value.joinToString(AUTHOR_DELIMITER)
    }

    @TypeConverter
    fun stringToAuthors(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(AUTHOR_DELIMITER)

    @TypeConverter
    fun mediaFormatHintToString(value: MediaFormatHint?): String? {
        value ?: return null
        val container = value.container.orEmpty()
        val codec = value.codec.orEmpty()
        require(FORMAT_DELIMITER !in container && FORMAT_DELIMITER !in codec) {
            "media format hint contains reserved delimiter"
        }
        return container + FORMAT_DELIMITER + codec
    }

    @TypeConverter
    fun stringToMediaFormatHint(value: String?): MediaFormatHint? {
        value ?: return null
        val fields = value.split(FORMAT_DELIMITER, limit = 2)
        require(fields.size == 2) { "invalid media format hint" }
        return MediaFormatHint(
            container = fields[0].ifEmpty { null },
            codec = fields[1].ifEmpty { null },
        )
    }

    private companion object {
        const val AUTHOR_DELIMITER = "\u001E"
        const val FORMAT_DELIMITER = "\u001F"
    }
}
