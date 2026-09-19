package com.audioape.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.audioape.core.model.ContentReference
import kotlinx.coroutines.flow.first

private val Context.libraryTreeGrantDataStore by preferencesDataStore(
    name = "library_tree_grant",
)

fun createLibraryTreeGrantStore(context: Context): LibraryTreeGrantStore =
    DataStoreLibraryTreeGrantStore(context.applicationContext.libraryTreeGrantDataStore)

class DataStoreLibraryTreeGrantStore(
    private val dataStore: DataStore<Preferences>,
) : LibraryTreeGrantStore {
    override suspend fun read(): StoredLibraryTreeGrant? {
        val preferences = dataStore.data.first()
        val tree = preferences[TREE_URI] ?: return null
        return StoredLibraryTreeGrant(
            tree = ContentReference(tree),
            flags =
                SafGrantFlags(
                    read = preferences[READ_PERMISSION] ?: false,
                    write = preferences[WRITE_PERMISSION] ?: false,
                ),
        )
    }

    override suspend fun write(grant: StoredLibraryTreeGrant) {
        dataStore.edit { preferences ->
            preferences[TREE_URI] = grant.tree.value
            preferences[READ_PERMISSION] = grant.flags.read
            preferences[WRITE_PERMISSION] = grant.flags.write
        }
    }

    private companion object {
        val TREE_URI = stringPreferencesKey("library_tree_uri")
        val READ_PERMISSION = booleanPreferencesKey("library_tree_read_permission")
        val WRITE_PERMISSION = booleanPreferencesKey("library_tree_write_permission")
    }
}
