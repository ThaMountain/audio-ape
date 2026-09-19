package com.audioape.player.playback

import android.content.Context
import androidx.core.content.edit
import com.audioape.core.database.AudioApeDatabase

/** Construction seam used by on-device tests to avoid the normal app database. */
internal object PlaybackServiceDependencies {
    private val productionDatabaseFactory: (Context) -> AudioApeDatabase =
        { context ->
            val testDatabaseName =
                context
                    .getSharedPreferences(TEST_CONFIGURATION, Context.MODE_PRIVATE)
                    .getString(TEST_DATABASE_NAME, null)
            AudioApeDatabase.create(context, testDatabaseName ?: AudioApeDatabase.DATABASE_NAME)
        }

    @Volatile
    var databaseFactory: (Context) -> AudioApeDatabase = productionDatabaseFactory

    fun resetDatabaseFactory() {
        databaseFactory = productionDatabaseFactory
    }

    /** Allows a debug instrumentation process to select an isolated DB for the remote service. */
    fun setTestDatabaseName(
        context: Context,
        databaseName: String?,
    ) {
        require(databaseName == null || (databaseName.isNotBlank() && '/' !in databaseName)) {
            "test database name must be a simple filename"
        }
        context
            .getSharedPreferences(TEST_CONFIGURATION, Context.MODE_PRIVATE)
            .edit(commit = true) {
                if (databaseName == null) {
                    remove(TEST_DATABASE_NAME)
                } else {
                    putString(TEST_DATABASE_NAME, databaseName)
                }
            }
    }

    private const val TEST_CONFIGURATION = "playback-service-test-configuration"
    private const val TEST_DATABASE_NAME = "database-name"
}
