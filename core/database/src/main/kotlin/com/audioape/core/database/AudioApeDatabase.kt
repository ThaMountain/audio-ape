package com.audioape.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

/**
 * Audio Ape's app-private source of truth for the local listening domain.
 *
 * Checkpoints, bookmarks, completion, collections, and history tombstones must remain in this
 * database. Callers must never serialize them beside user-owned media in the external SAF tree.
 */
@Database(
    entities = [
        WorkEntity::class,
        AudioEditionEntity::class,
        ExternalAliasEntity::class,
        LibraryBookEntity::class,
        MediaPartEntity::class,
        ChapterEntity::class,
        PlaybackCheckpointEntity::class,
        BookmarkEntity::class,
        CollectionEntity::class,
        CollectionItemEntity::class,
        BookHistoryTombstoneEntity::class,
        LibrarySearchContentEntity::class,
        LibrarySearchFtsEntity::class,
    ],
    version = AudioApeDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AudioApeDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    abstract fun libraryBookDao(): LibraryBookDao

    abstract fun mediaPartDao(): MediaPartDao

    abstract fun chapterDao(): ChapterDao

    abstract fun playbackCheckpointDao(): PlaybackCheckpointDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun collectionDao(): CollectionDao

    abstract fun bookHistoryDao(): BookHistoryDao

    abstract fun librarySearchDao(): LibrarySearchDao

    companion object {
        const val VERSION = 1
        const val DATABASE_NAME = "audio-ape.db"

        /** Initial schema has no predecessor; future versions append explicit migrations here. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun create(
            context: Context,
            name: String = DATABASE_NAME,
        ): AudioApeDatabase =
            Room
                .databaseBuilder(context.applicationContext, AudioApeDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
