package com.audioape.core.storage

import com.audioape.core.model.ContentReference
import java.io.InputStream
import java.io.OutputStream

/** Read/write permission bits granted by the system picker. */
data class SafGrantFlags(
    val read: Boolean,
    val write: Boolean,
) {
    val providesLibraryAccess: Boolean
        get() = read && write

    companion object {
        val NONE = SafGrantFlags(read = false, write = false)
        val READ_WRITE = SafGrantFlags(read = true, write = true)
    }
}

/** The app-private pointer to the user-selected document tree. */
data class StoredLibraryTreeGrant(
    val tree: ContentReference,
    val flags: SafGrantFlags,
)

/** Metadata needed for best-effort rejection of unsupported picker roots. */
data class TreeDescriptor(
    val authority: String,
    val documentId: String,
)

/** Provider capabilities observed without creating, moving, or deleting a document. */
data class TreeCapabilities(
    val exists: Boolean,
    val isDirectory: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val supportsChildCreation: Boolean,
)

/** App-private persistence boundary; the production implementation uses Preferences DataStore. */
interface LibraryTreeGrantStore {
    suspend fun read(): StoredLibraryTreeGrant?

    suspend fun write(grant: StoredLibraryTreeGrant)
}

/** Android SAF boundary kept replaceable so selection behavior is deterministic in unit tests. */
interface SafTreeProvider {
    fun describeTree(tree: ContentReference): TreeDescriptor?

    fun takePersistablePermission(
        tree: ContentReference,
        flags: SafGrantFlags,
    )

    fun persistedPermission(tree: ContentReference): SafGrantFlags?

    fun inspectTree(tree: ContentReference): TreeCapabilities
}

/**
 * Reusable SAF document primitives for later import-journal work.
 *
 * This deliberately has no delete or move operation. Callers must perform stream work off the
 * main thread and close returned streams.
 */
interface SafDocumentOperations {
    fun createDirectory(
        parent: ContentReference,
        displayName: String,
    ): ContentReference

    fun createFile(
        parent: ContentReference,
        mimeType: String,
        displayName: String,
    ): ContentReference

    fun openInput(document: ContentReference): InputStream

    fun openOutput(
        document: ContentReference,
        truncate: Boolean = true,
    ): OutputStream

    fun rename(
        document: ContentReference,
        displayName: String,
    ): ContentReference
}

enum class TreeUnavailableReason {
    INVALID_TREE_URI,
    PROVIDER_UNAVAILABLE,
    NOT_FOUND,
    NOT_A_DIRECTORY,
    NOT_READABLE,
    NOT_WRITABLE,
    CHILD_CREATION_UNSUPPORTED,
    GRANT_STORE_FAILURE,
}

sealed interface LibraryTreeAccessResult {
    data class Granted(
        val grant: StoredLibraryTreeGrant,
    ) : LibraryTreeAccessResult

    data object Denied : LibraryTreeAccessResult

    data class PermissionMissing(
        val tree: ContentReference?,
        val readMissing: Boolean,
        val writeMissing: Boolean,
    ) : LibraryTreeAccessResult

    data class RootNotSupported(
        val tree: ContentReference,
    ) : LibraryTreeAccessResult

    data class Unavailable(
        val tree: ContentReference?,
        val reason: TreeUnavailableReason,
    ) : LibraryTreeAccessResult
}
