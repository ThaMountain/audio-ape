package com.audioape.core.storage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryTreePickerTest {
    @Test
    fun `picker intent requests a persistable read write document tree grant`() {
        val initial = Uri.parse("content://com.android.externalstorage.documents/root/primary")

        val intent = LibraryTreePicker.createIntent(initial)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
        assertEquals(
            initial,
            intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
        )
    }

    @Test
    fun `result decoding preserves only read write bits actually returned`() {
        val uri =
            Uri.parse(
                "content://com.android.externalstorage.documents/" +
                    "tree/primary%3AAudiobooks%2FAudio%20Ape",
            )
        val intent =
            Intent().apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        val result = LibraryTreePicker.decodeResult(Activity.RESULT_OK, intent)

        assertEquals(
            PickerResult.Selected(
                tree =
                    com.audioape.core.model
                        .ContentReference(uri.toString()),
                flags = SafGrantFlags(read = true, write = false),
            ),
            result,
        )
    }

    @Test
    fun `cancelled activity result decodes as denial`() {
        val result = LibraryTreePicker.decodeResult(Activity.RESULT_CANCELED, Intent())

        assertSame(PickerResult.Denied, result)
        assertFalse(result is PickerResult.Selected)
    }
}
