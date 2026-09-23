package com.audioape.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadManifestTest {
    @Test
    fun codecRoundTripPreservesEverything() {
        val manifest =
            DownloadManifest(
                bookId = "6c1b1ce2-8d5f-4a3e-9c47-1234567890ab",
                displayTitle = "A Tale = of % escapes\n and spaces on the second line",
                parts =
                    listOf(
                        PartDescriptor(0, "part00.bin", 4096L, "0".repeat(64)),
                        PartDescriptor(1, "part01.bin", 8192L, "1".repeat(64)),
                    ),
            )
        val decoded = DownloadManifestCodec.decode(DownloadManifestCodec.encode(manifest))
        assertEquals(manifest, decoded)
    }

    @Test
    fun rejectsTraversalOrAbsoluteFilenamesByGrammar() {
        for (bad in listOf("../evil.bin", "/abs.bin", "a/b.bin", "C:/win.bin", "a\\b.bin", "")) {
            try {
                PartDescriptor(0, bad, 1L, "0".repeat(64))
                throw AssertionError("must reject '$bad'")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun rejectsDuplicateNamesAndOrdersAndOversize() {
        try {
            DownloadManifest(
                "b",
                "t",
                listOf(
                    PartDescriptor(0, "a.bin", 1L, "0".repeat(64)),
                    PartDescriptor(1, "A.BIN", 1L, "0".repeat(64)),
                ),
            )
            throw AssertionError("duplicate case-insensitive names must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            DownloadManifest(
                "b",
                "t",
                listOf(
                    PartDescriptor(0, "a.bin", 1L, "0".repeat(64)),
                    PartDescriptor(0, "b.bin", 1L, "0".repeat(64)),
                ),
            )
            throw AssertionError("duplicate orders must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun decoderRejectsMalformedManifests() {
        val good =
            "0".repeat(64)
        val valid =
            listOf(
                "# audioape download manifest v1",
                "book-id=6c1b1ce2-8d5f-4a3e-9c47-1234567890ab",
                "title=Hello",
                "part-count=1",
                "part|0|part00.bin|4|$good",
            ).joinToString("\n")
        val decoded = DownloadManifestCodec.decode(valid)
        assertEquals(1, decoded.parts.size)

        for (bad in listOf(
            valid.replace(HEADER, "# wrong header"),
            valid.replace("part-count=1", "part-count=2"),
            valid.replace("part|0|part00.bin|4|$good", "part|0|part00.bin|notanumber|$good"),
            valid.replace("part|0|part00.bin|4|$good", "part|0|../evil.bin|4|$good"),
            valid.replace("book-id=6c1b1ce2-8d5f-4a3e-9c47-1234567890ab", "book-id="),
        )) {
            try {
                DownloadManifestCodec.decode(bad)
                throw AssertionError("must reject malformed manifest")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    private companion object {
        const val HEADER = "# audioape download manifest v1"
    }
}

class ArchiveSafetyTest {
    @Test
    fun rejectsEveryEscalationShape() {
        val rejected =
            listOf(
                "../evil",
                "a/../../evil",
                "./x",
                "a/./x",
                "/abs",
                "//abs",
                "C:\\x",
                "c:/x",
                "a\\b",
                "..",
                ".",
                "",
                "x".repeat(256),
            )
        rejected.forEach { name -> assertNotNull("must reject '$name'", ArchiveNameChecker.rejectReason(name)) }
    }

    @Test
    fun acceptsBareSafeNames() {
        for (name in listOf("a.bin", "part01.M4A", "a-b_c.d-e", "manifest.mf")) {
            assertEquals(null, ArchiveNameChecker.rejectReason(name))
        }
    }
}
