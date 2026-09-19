package com.audioape.core.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.audioape.core.model.ContentReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreLibraryTreeGrantStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `grant round trips through preferences DataStore`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore =
                    PreferenceDataStoreFactory.create(scope = scope) {
                        File(temporaryFolder.root, "library_tree_grant.preferences_pb")
                    }
                val store = DataStoreLibraryTreeGrantStore(dataStore)
                val expected =
                    StoredLibraryTreeGrant(
                        tree =
                            ContentReference(
                                "content://com.android.externalstorage.documents/" +
                                    "tree/primary%3AAudiobooks%2FAudio%20Ape",
                            ),
                        flags = SafGrantFlags.READ_WRITE,
                    )

                assertNull(store.read())
                store.write(expected)

                assertEquals(expected, store.read())
            } finally {
                scope.cancel()
            }
        }
}
