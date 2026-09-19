package com.audioape.core.storage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.audioape.core.model.ContentReference

object LibraryTreePicker {
    const val SUGGESTED_PARENT_FOLDER = "Audiobooks"
    const val SUGGESTED_LIBRARY_FOLDER = "Audio Ape"

    /**
     * Builds the system document-tree picker request. Android offers no API to pre-create or force
     * a path; UI should ask the user to select/create an `Audiobooks/Audio Ape` child folder.
     */
    fun createIntent(initialLocation: Uri? = null): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            initialLocation?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    /** Preserves the exact read/write bits returned by the picker result Intent. */
    fun decodeResult(
        resultCode: Int,
        data: Intent?,
    ): PickerResult {
        if (resultCode != Activity.RESULT_OK || data?.data == null) return PickerResult.Denied
        return PickerResult.Selected(
            tree = ContentReference(data.data.toString()),
            flags = data.flags.toSafGrantFlags(),
        )
    }
}

sealed interface PickerResult {
    data class Selected(
        val tree: ContentReference,
        val flags: SafGrantFlags,
    ) : PickerResult

    data object Denied : PickerResult
}

internal fun Int.toSafGrantFlags() =
    SafGrantFlags(
        read = this and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        write = this and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
    )

internal fun SafGrantFlags.toAndroidFlags(): Int =
    (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
        (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
