package com.audioape.core.storage

import com.audioape.core.model.ContentReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream

class SafeCopyImporterTest {
    private val source = ContentReference("content://source/document/book%3Aone")
    private val destinationTree = ContentReference("content://library/tree/Audio%20Ape")

    @Test
    fun `copy streams off caller thread and verifies known source and destination lengths`() =
        runBlocking {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            val provider = FakeImportDocuments(source, bytes)
            val callerThread = Thread.currentThread()

            val result = importer(provider).copy(request())

            val success = result as SafeCopyResult.Success
            assertEquals(bytes.size.toLong(), success.imported.bytesCopied)
            assertEquals("A Book.mp3", success.imported.displayName)
            assertArrayEquals(bytes, provider.destinationBytes(success.imported.document))
            assertNotEquals(callerThread, provider.readThread)
            assertEquals(SafeCopyImporter.COPY_BUFFER_BYTES, provider.largestRequestedRead)
            assertFalse(provider.sourceWasRenamed)
            assertArrayEquals(bytes, provider.sourceBytes)
            assertNull(provider.deletedDestination)
        }

    @Test
    fun `failed read removes only the partial destination and leaves source untouched`() =
        runBlocking {
            val bytes = "authorized fixture bytes".toByteArray()
            val provider = FakeImportDocuments(source, bytes).apply { failReadAfterFirstChunk = true }

            val result = importer(provider).copy(request())

            assertEquals(
                SafeCopyResult.Failure(
                    SafeCopyError.IoFailure(CopyIoStage.READ_SOURCE),
                    PartialCleanupStatus.REMOVED,
                ),
                result,
            )
            assertEquals(provider.createdDestination, provider.deletedDestination)
            assertFalse(provider.sourceWasRenamed)
            assertArrayEquals(bytes, provider.sourceBytes)
            assertTrue(provider.destinationsAreEmpty())
        }

    @Test
    fun `timed out read removes its partial destination`() =
        runBlocking {
            val provider = FakeImportDocuments(source, byteArrayOf(1, 2, 3)).apply { blockRead = true }

            val result = importer(provider, timeoutMillis = 100L).copy(request())

            assertEquals(
                SafeCopyResult.Failure(
                    SafeCopyError.TimedOut,
                    PartialCleanupStatus.REMOVED,
                ),
                result,
            )
            assertEquals(provider.createdDestination, provider.deletedDestination)
            assertArrayEquals(byteArrayOf(1, 2, 3), provider.sourceBytes)
        }

    @Test
    fun `revoked transient source grant returns clear error without creating destination`() =
        runBlocking {
            val provider =
                FakeImportDocuments(source, byteArrayOf(1)).apply {
                    sourcePermissionDenied = true
                }

            val result = importer(provider).copy(request())

            assertEquals(
                SafeCopyResult.Failure(
                    SafeCopyError.SourcePermissionDenied,
                    PartialCleanupStatus.NOT_NEEDED,
                ),
                result,
            )
            assertNull(provider.createdDestination)
            assertNull(provider.deletedDestination)
        }

    @Test
    fun `collision uses a case insensitive numbered name without overwriting`() =
        runBlocking {
            val provider =
                FakeImportDocuments(source, byteArrayOf(4, 5, 6)).apply {
                    existingNames += "A Book.mp3"
                    existingNames += "a book (2).mp3"
                }

            val result = importer(provider).copy(request()) as SafeCopyResult.Success

            assertEquals("A Book (3).mp3", result.imported.displayName)
            assertEquals(setOf("A Book.mp3", "a book (2).mp3"), provider.existingNames)
        }

    @Test
    fun `unsafe long requested name is sanitized and truncated while retaining extension`() =
        runBlocking {
            val provider = FakeImportDocuments(source, byteArrayOf(7))
            val requested = "../" + "Very Long\\Title".repeat(20) + ".m4b"

            val result =
                importer(provider)
                    .copy(request().copy(destinationDisplayName = requested)) as SafeCopyResult.Success

            assertTrue(result.imported.displayName.length <= SafeCopyImporter.MAX_DISPLAY_NAME_LENGTH)
            assertTrue(result.imported.displayName.endsWith(".m4b"))
            assertFalse('/' in result.imported.displayName)
            assertFalse('\\' in result.imported.displayName)
        }

    @Test
    fun `known source length mismatch removes partial destination`() =
        runBlocking {
            val provider =
                FakeImportDocuments(source, byteArrayOf(8, 9)).apply {
                    reportedSourceSize = 3L
                }

            val result = importer(provider).copy(request())

            assertEquals(
                SafeCopyResult.Failure(
                    SafeCopyError.LengthMismatch(3L, 2L, LengthObservation.SOURCE_METADATA),
                    PartialCleanupStatus.REMOVED,
                ),
                result,
            )
            assertTrue(provider.destinationsAreEmpty())
        }

    private fun importer(
        provider: FakeImportDocuments,
        timeoutMillis: Long = 5_000L,
    ) = SafeCopyImporter(
        documents = provider,
        inspection = provider,
        cleanup = provider,
        ioDispatcher = Dispatchers.IO,
        timeoutMillis = timeoutMillis,
    )

    private fun request() =
        SafeCopyRequest(
            source = TransientImportSource(source),
            destinationTree = destinationTree,
        )

    private class FakeImportDocuments(
        private val source: ContentReference,
        val sourceBytes: ByteArray,
    ) : SafDocumentOperations,
        SafImportDocumentInspection,
        PartialDestinationCleanup {
        val existingNames = linkedSetOf<String>()
        var reportedSourceSize: Long? = sourceBytes.size.toLong()
        var sourcePermissionDenied = false
        var failReadAfterFirstChunk = false
        var blockRead = false
        var sourceWasRenamed = false
        var createdDestination: ContentReference? = null
        var deletedDestination: ContentReference? = null
        var readThread: Thread? = null
        var largestRequestedRead = 0
        private val createdNames = linkedMapOf<ContentReference, String>()
        private val destinationStreams = linkedMapOf<ContentReference, ByteArrayOutputStream>()

        override fun createDirectory(
            parent: ContentReference,
            displayName: String,
        ): ContentReference = error("not used")

        override fun createFile(
            parent: ContentReference,
            mimeType: String,
            displayName: String,
        ): ContentReference {
            check(parent.value == "content://library/tree/Audio%20Ape")
            check(existingNames.none { it.equals(displayName, ignoreCase = true) })
            val destination = ContentReference("content://library/document/import-${createdNames.size + 1}")
            createdDestination = destination
            createdNames[destination] = displayName
            destinationStreams[destination] = ByteArrayOutputStream()
            return destination
        }

        override fun openInput(document: ContentReference): InputStream {
            check(document == source)
            if (sourcePermissionDenied) throw SecurityException("grant revoked")
            return when {
                blockRead -> {
                    BlockingInputStream()
                }

                failReadAfterFirstChunk -> {
                    FailingInputStream(sourceBytes)
                }

                else -> {
                    object : ByteArrayInputStream(sourceBytes) {
                        override fun read(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            readThread = Thread.currentThread()
                            largestRequestedRead = maxOf(largestRequestedRead, length)
                            return super.read(buffer, offset, length)
                        }
                    }
                }
            }
        }

        override fun openOutput(
            document: ContentReference,
            truncate: Boolean,
        ): OutputStream = checkNotNull(destinationStreams[document])

        override fun rename(
            document: ContentReference,
            displayName: String,
        ): ContentReference {
            if (document == source) sourceWasRenamed = true
            error("rename must not be used by importer")
        }

        override fun inspectDocument(document: ContentReference): SafDocumentMetadata {
            if (document == source) {
                if (sourcePermissionDenied) throw SecurityException("grant revoked")
                return SafDocumentMetadata("A Book.mp3", "audio/mpeg", reportedSourceSize)
            }
            return SafDocumentMetadata(
                displayName = createdNames[document],
                mimeType = "audio/mpeg",
                sizeBytes = destinationStreams[document]?.size()?.toLong(),
            )
        }

        override fun listChildDisplayNames(parent: ContentReference): Set<String> {
            check(parent.value == "content://library/tree/Audio%20Ape")
            return existingNames + createdNames.values
        }

        override fun deletePartialDestination(destination: ImporterOwnedDestination): Boolean {
            check(destination.document != source)
            deletedDestination = destination.document
            createdNames.remove(destination.document)
            return destinationStreams.remove(destination.document) != null
        }

        fun destinationBytes(document: ContentReference): ByteArray = checkNotNull(destinationStreams[document]).toByteArray()

        fun destinationsAreEmpty(): Boolean = destinationStreams.isEmpty()

        private inner class FailingInputStream(
            bytes: ByteArray,
        ) : InputStream() {
            private val delegate = ByteArrayInputStream(bytes)
            private var firstRead = true

            override fun read(): Int = error("bulk read expected")

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                readThread = Thread.currentThread()
                largestRequestedRead = maxOf(largestRequestedRead, length)
                if (!firstRead) throw IOException("fixture read failed")
                firstRead = false
                return delegate.read(buffer, offset, minOf(3, length))
            }
        }

        private inner class BlockingInputStream : InputStream() {
            override fun read(): Int = error("bulk read expected")

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                readThread = Thread.currentThread()
                largestRequestedRead = maxOf(largestRequestedRead, length)
                try {
                    Thread.sleep(10_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("fixture interrupted")
                }
                return -1
            }
        }
    }
}
