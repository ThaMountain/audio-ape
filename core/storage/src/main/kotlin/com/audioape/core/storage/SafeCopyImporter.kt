package com.audioape.core.storage

import com.audioape.core.model.ContentReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale

data class SafeCopyRequest(
    val source: TransientImportSource,
    val destinationTree: ContentReference,
    val destinationDisplayName: String? = null,
    val mimeType: String? = null,
)

data class ImportedDocument(
    val document: ContentReference,
    val displayName: String,
    val bytesCopied: Long,
)

enum class PartialCleanupStatus {
    NOT_NEEDED,
    REMOVED,
    REMOVE_FAILED,
}

enum class CopyIoStage {
    INSPECT_SOURCE,
    OPEN_SOURCE,
    LIST_DESTINATION,
    CREATE_DESTINATION,
    OPEN_DESTINATION,
    READ_SOURCE,
    WRITE_DESTINATION,
    VERIFY_DESTINATION,
}

enum class LengthObservation {
    SOURCE_METADATA,
    DESTINATION_METADATA,
}

sealed interface SafeCopyError {
    data object SourcePermissionDenied : SafeCopyError

    data class IoFailure(
        val stage: CopyIoStage,
    ) : SafeCopyError

    data object TimedOut : SafeCopyError

    data class LengthMismatch(
        val expectedBytes: Long,
        val actualBytes: Long,
        val observation: LengthObservation,
    ) : SafeCopyError
}

sealed interface SafeCopyResult {
    data class Success(
        val imported: ImportedDocument,
    ) : SafeCopyResult

    data class Failure(
        val error: SafeCopyError,
        val cleanupStatus: PartialCleanupStatus,
    ) : SafeCopyResult
}

/**
 * Copies one transient source into the managed library tree without mutating the source.
 *
 * Blocking provider calls and bounded-buffer streaming run on [ioDispatcher]. A failed or timed-out
 * copy asks [cleanup] to delete only the destination reference created by this invocation.
 */
class SafeCopyImporter(
    private val documents: SafDocumentOperations,
    private val inspection: SafImportDocumentInspection,
    private val cleanup: PartialDestinationCleanup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val destinationMutex = Mutex()

    init {
        require(timeoutMillis > 0L) { "copy timeout must be greater than zero" }
    }

    suspend fun copy(request: SafeCopyRequest): SafeCopyResult =
        destinationMutex.withLock {
            withContext(ioDispatcher) {
                copyOffMainThread(request)
            }
        }

    private suspend fun copyOffMainThread(request: SafeCopyRequest): SafeCopyResult {
        var createdDestination: ImporterOwnedDestination? = null
        var stage = CopyIoStage.INSPECT_SOURCE
        return try {
            val imported =
                withTimeout(timeoutMillis) {
                    runInterruptible {
                        val sourceMetadata = inspection.inspectDocument(request.source.document)
                        stage = CopyIoStage.OPEN_SOURCE
                        documents.openInput(request.source.document).use { input ->
                            stage = CopyIoStage.LIST_DESTINATION
                            val occupiedNames = inspection.listChildDisplayNames(request.destinationTree)
                            val destinationName =
                                chooseUniqueDisplayName(
                                    requested =
                                        request.destinationDisplayName
                                            ?: sourceMetadata.displayName
                                            ?: DEFAULT_DISPLAY_NAME,
                                    occupiedNames = occupiedNames,
                                )
                            stage = CopyIoStage.CREATE_DESTINATION
                            val created =
                                documents.createFile(
                                    parent = request.destinationTree,
                                    mimeType =
                                        request.mimeType
                                            ?: sourceMetadata.mimeType
                                            ?: DEFAULT_MIME_TYPE,
                                    displayName = destinationName,
                                )
                            createdDestination = ImporterOwnedDestination(created)

                            stage = CopyIoStage.OPEN_DESTINATION
                            var copiedBytes = 0L
                            documents.openOutput(created, truncate = true).use { output ->
                                val buffer = ByteArray(COPY_BUFFER_BYTES)
                                while (true) {
                                    stage = CopyIoStage.READ_SOURCE
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    stage = CopyIoStage.WRITE_DESTINATION
                                    output.write(buffer, 0, count)
                                    copiedBytes = Math.addExact(copiedBytes, count.toLong())
                                }
                                output.flush()
                            }

                            sourceMetadata.sizeBytes?.let { expected ->
                                if (copiedBytes != expected) {
                                    throw CopyLengthMismatch(
                                        expected,
                                        copiedBytes,
                                        LengthObservation.SOURCE_METADATA,
                                    )
                                }
                            }

                            stage = CopyIoStage.VERIFY_DESTINATION
                            inspection.inspectDocument(created).sizeBytes?.let { destinationBytes ->
                                if (destinationBytes != copiedBytes) {
                                    throw CopyLengthMismatch(
                                        copiedBytes,
                                        destinationBytes,
                                        LengthObservation.DESTINATION_METADATA,
                                    )
                                }
                            }
                            ImportedDocument(created, destinationName, copiedBytes)
                        }
                    }
                }
            SafeCopyResult.Success(imported)
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) {
                cleanupCreatedDestination(createdDestination)
                throw timeout
            }
            failure(SafeCopyError.TimedOut, createdDestination)
        } catch (_: SecurityException) {
            val error =
                if (stage == CopyIoStage.INSPECT_SOURCE || stage == CopyIoStage.OPEN_SOURCE) {
                    SafeCopyError.SourcePermissionDenied
                } else {
                    SafeCopyError.IoFailure(stage)
                }
            failure(error, createdDestination)
        } catch (mismatch: CopyLengthMismatch) {
            failure(
                SafeCopyError.LengthMismatch(
                    mismatch.expectedBytes,
                    mismatch.actualBytes,
                    mismatch.observation,
                ),
                createdDestination,
            )
        } catch (_: InterruptedIOException) {
            if (!currentCoroutineContext().isActive) {
                cleanupCreatedDestination(createdDestination)
                currentCoroutineContext().ensureActive()
            }
            failure(SafeCopyError.TimedOut, createdDestination)
        } catch (cancelled: CancellationException) {
            cleanupCreatedDestination(createdDestination)
            throw cancelled
        } catch (_: IOException) {
            failure(SafeCopyError.IoFailure(stage), createdDestination)
        } catch (_: ArithmeticException) {
            failure(SafeCopyError.IoFailure(stage), createdDestination)
        } catch (_: RuntimeException) {
            failure(SafeCopyError.IoFailure(stage), createdDestination)
        }
    }

    private suspend fun failure(
        error: SafeCopyError,
        createdDestination: ImporterOwnedDestination?,
    ): SafeCopyResult.Failure = SafeCopyResult.Failure(error, cleanupCreatedDestination(createdDestination))

    private suspend fun cleanupCreatedDestination(createdDestination: ImporterOwnedDestination?): PartialCleanupStatus =
        if (createdDestination == null) {
            PartialCleanupStatus.NOT_NEEDED
        } else {
            withContext(NonCancellable + ioDispatcher) {
                try {
                    if (cleanup.deletePartialDestination(createdDestination)) {
                        PartialCleanupStatus.REMOVED
                    } else {
                        PartialCleanupStatus.REMOVE_FAILED
                    }
                } catch (_: Exception) {
                    PartialCleanupStatus.REMOVE_FAILED
                }
            }
        }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 120
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val DEFAULT_DISPLAY_NAME = "Imported audio"
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"

        internal fun chooseUniqueDisplayName(
            requested: String,
            occupiedNames: Set<String>,
        ): String {
            val safeName = sanitizeDisplayName(requested)
            val occupied = occupiedNames.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
            var ordinal = 1
            while (true) {
                val candidate = fitDisplayName(safeName, ordinal)
                if (candidate.lowercase(Locale.ROOT) !in occupied) return candidate
                ordinal++
            }
        }

        private fun sanitizeDisplayName(requested: String): String {
            val sanitized =
                requested
                    .map { character ->
                        if (
                            character == '/' ||
                            character == '\\' ||
                            character == '\u0000' ||
                            character.isISOControl()
                        ) {
                            '_'
                        } else {
                            character
                        }
                    }.joinToString("")
                    .trim()
                    .trim('.')
            return sanitized.ifBlank { DEFAULT_DISPLAY_NAME }
        }

        private fun fitDisplayName(
            safeName: String,
            ordinal: Int,
        ): String {
            val suffix = if (ordinal == 1) "" else " ($ordinal)"
            val dot = safeName.lastIndexOf('.')
            val hasExtension = dot in 1 until safeName.lastIndex && safeName.length - dot <= 17
            val extension = if (hasExtension) safeName.substring(dot) else ""
            val base = if (hasExtension) safeName.substring(0, dot) else safeName
            val allowedBaseLength =
                (MAX_DISPLAY_NAME_LENGTH - suffix.length - extension.length).coerceAtLeast(1)
            return base.take(allowedBaseLength) + suffix + extension
        }
    }

    private class CopyLengthMismatch(
        val expectedBytes: Long,
        val actualBytes: Long,
        val observation: LengthObservation,
    ) : IOException()
}
