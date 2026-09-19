package com.audioape.core.storage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.audioape.core.model.ContentReference
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
class ImportSourcePickerTest {
    @Test
    fun `picker requests one transient readable document`() {
        val intent = ImportSourcePicker.createIntent(listOf("audio/mpeg", "audio/mp4"))

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertEquals("*/*", intent.type)
        assertEquals(
            listOf("audio/mpeg", "audio/mp4"),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList(),
        )
    }

    @Test
    fun `readable result becomes an opaque transient source`() {
        val uri = Uri.parse("content://provider/document/book%3Aone")
        val result =
            ImportSourcePicker.decodeResult(
                Activity.RESULT_OK,
                Intent().apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )

        assertEquals(
            ImportSourcePickerResult.Selected(
                TransientImportSource(ContentReference(uri.toString())),
            ),
            result,
        )
    }

    @Test
    fun `cancelled result is distinct from denied result`() {
        assertSame(
            ImportSourcePickerResult.Cancelled,
            ImportSourcePicker.decodeResult(Activity.RESULT_CANCELED, null),
        )
        assertSame(
            ImportSourcePickerResult.Denied,
            ImportSourcePicker.decodeResult(Activity.RESULT_OK, Intent()),
        )
    }

    @Test
    fun `result without transient read grant is denied`() {
        val result =
            ImportSourcePicker.decodeResult(
                Activity.RESULT_OK,
                Intent().apply { data = Uri.parse("content://provider/document/no-read") },
            )

        assertSame(ImportSourcePickerResult.Denied, result)
    }
}
