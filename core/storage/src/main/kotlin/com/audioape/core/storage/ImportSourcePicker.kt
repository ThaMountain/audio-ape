package com.audioape.core.storage

import android.app.Activity
import android.content.Intent
import com.audioape.core.model.ContentReference

/** A user-selected import source that is intentionally never stored as the library tree grant. */
data class TransientImportSource(
    val document: ContentReference,
)

object ImportSourcePicker {
    /**
     * Requests one readable document without persistable or write permission. The selection remains
     * transient; callers should copy it while the activity grant is valid.
     */
    fun createIntent(mimeTypes: List<String> = listOf("audio/*")): Intent {
        require(mimeTypes.isNotEmpty() && mimeTypes.none(String::isBlank)) {
            "at least one nonblank MIME type is required"
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = if (mimeTypes.size == 1) mimeTypes.single() else "*/*"
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
        }
    }

    fun decodeResult(
        resultCode: Int,
        data: Intent?,
    ): ImportSourcePickerResult {
        if (resultCode == Activity.RESULT_CANCELED) return ImportSourcePickerResult.Cancelled
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            return ImportSourcePickerResult.Denied
        }
        if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return ImportSourcePickerResult.Denied
        }
        return ImportSourcePickerResult.Selected(
            TransientImportSource(ContentReference(data.data.toString())),
        )
    }
}

sealed interface ImportSourcePickerResult {
    data class Selected(
        val source: TransientImportSource,
    ) : ImportSourcePickerResult

    data object Cancelled : ImportSourcePickerResult

    data object Denied : ImportSourcePickerResult
}
