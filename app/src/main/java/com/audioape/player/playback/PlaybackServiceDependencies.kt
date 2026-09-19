package com.audioape.player.playback

import android.content.Context
import com.audioape.core.database.AudioApeDatabase

/** Process-local construction seam used by the on-device test to avoid the real app database. */
internal object PlaybackServiceDependencies {
    private val productionDatabaseFactory: (Context) -> AudioApeDatabase =
        { context -> AudioApeDatabase.create(context) }

    @Volatile
    var databaseFactory: (Context) -> AudioApeDatabase = productionDatabaseFactory

    fun resetDatabaseFactory() {
        databaseFactory = productionDatabaseFactory
    }
}
