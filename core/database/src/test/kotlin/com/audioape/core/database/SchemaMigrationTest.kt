package com.audioape.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SchemaMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AudioApeDatabase::class.java,
        )

    @Test
    fun versionOneSchemaOpensAndMatchesCommittedExport() {
        context.deleteDatabase(TEST_DATABASE)
        helper.createDatabase(TEST_DATABASE, 1).close()

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                AudioApeDatabase.VERSION,
                true,
                *AudioApeDatabase.MIGRATIONS,
            )
        helper.closeWhenFinished(database)

        val expectedTables =
            setOf(
                "work",
                "audio_edition",
                "external_alias",
                "library_book",
                "media_part",
                "chapter",
                "playback_checkpoint",
                "bookmark",
                "collection",
                "collection_item",
                "book_history_tombstone",
                "library_search_content",
                "library_search_fts",
            )
        val actualTables = mutableSetOf<String>()
        database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) actualTables += cursor.getString(0)
        }
        assertTrue(actualTables.containsAll(expectedTables))

        database
            .query(
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type = 'trigger' AND tbl_name = 'library_search_content'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }

        database.query("PRAGMA foreign_key_list(media_part)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("library_book", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("book_id", cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
        }

        var foundUniquePartOrder = false
        database.query("PRAGMA index_list(media_part)").use { cursor ->
            while (cursor.moveToNext()) {
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
                val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (!unique) continue
                val columns = mutableListOf<String>()
                database.query("PRAGMA index_info(`$indexName`)").use { indexCursor ->
                    while (indexCursor.moveToNext()) {
                        columns += indexCursor.getString(indexCursor.getColumnIndexOrThrow("name"))
                    }
                }
                if (columns == listOf("book_id", "part_order")) foundUniquePartOrder = true
            }
        }
        assertTrue(foundUniquePartOrder)

        database.query("PRAGMA table_info(playback_checkpoint)").use { cursor ->
            var sawIntent = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "last_playing_intent") {
                    sawIntent = true
                    assertNull(cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertTrue(sawIntent)
        }

        database.query("PRAGMA table_info(book_history_tombstone)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertFalse(columns.any { it.contains("uri") || it.contains("path") || it.contains("media") })
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-aa-010"
    }
}
