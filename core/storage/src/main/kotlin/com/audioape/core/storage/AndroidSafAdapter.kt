package com.audioape.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.audioape.core.model.ContentReference
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

class AndroidSafAdapter(
    context: Context,
) : SafTreeProvider,
    SafDocumentOperations,
    SafImportDocumentInspection,
    PartialDestinationCleanup {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    override fun describeTree(tree: ContentReference): TreeDescriptor? {
        val uri = tree.toUri()
        if (!DocumentsContract.isTreeUri(uri)) return null
        val authority = uri.authority ?: return null
        val documentId =
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return null
        if (documentId.isBlank()) return null
        return TreeDescriptor(authority = authority, documentId = documentId)
    }

    override fun takePersistablePermission(
        tree: ContentReference,
        flags: SafGrantFlags,
    ) {
        contentResolver.takePersistableUriPermission(tree.toUri(), flags.toAndroidFlags())
    }

    override fun persistedPermission(tree: ContentReference): SafGrantFlags? {
        val target = tree.toUri()
        val permission =
            contentResolver.persistedUriPermissions.firstOrNull { it.uri == target }
                ?: return null
        return SafGrantFlags(read = permission.isReadPermission, write = permission.isWritePermission)
    }

    override fun inspectTree(tree: ContentReference): TreeCapabilities {
        val treeUri = tree.toUri()
        val descriptor = describeTree(tree) ?: return inaccessibleTree()
        val documentUri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                descriptor.documentId,
            )

        var exists = false
        var isDirectory = false
        var flags = 0
        contentResolver
            .query(
                documentUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    exists = true
                    isDirectory = cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                    flags = cursor.getInt(2)
                }
            }

        val readable = exists && isDirectory && canQueryChildren(treeUri, descriptor.documentId)
        val supportsCreate =
            flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        val documentFileWritable = DocumentFile.fromTreeUri(appContext, treeUri)?.canWrite() == true
        return TreeCapabilities(
            exists = exists,
            isDirectory = isDirectory,
            readable = readable,
            writable = documentFileWritable && supportsCreate,
            supportsChildCreation = supportsCreate,
        )
    }

    override fun createDirectory(
        parent: ContentReference,
        displayName: String,
    ): ContentReference =
        createDocument(
            parent = parent,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            displayName = displayName,
        )

    override fun createFile(
        parent: ContentReference,
        mimeType: String,
        displayName: String,
    ): ContentReference {
        require(mimeType.isNotBlank()) { "MIME type must not be blank" }
        return createDocument(parent, mimeType, displayName)
    }

    override fun openInput(document: ContentReference): InputStream =
        contentResolver.openInputStream(document.toUri())
            ?: throw FileNotFoundException("Provider returned no input stream")

    override fun openOutput(
        document: ContentReference,
        truncate: Boolean,
    ): OutputStream =
        contentResolver.openOutputStream(
            document.toUri(),
            if (truncate) "wt" else "wa",
        ) ?: throw FileNotFoundException("Provider returned no output stream")

    override fun rename(
        document: ContentReference,
        displayName: String,
    ): ContentReference {
        requireValidDisplayName(displayName)
        val renamed =
            DocumentsContract.renameDocument(
                contentResolver,
                document.toDocumentUri(),
                displayName,
            ) ?: throw FileNotFoundException("Provider refused to rename the document")
        return ContentReference(renamed.toString())
    }

    override fun inspectDocument(document: ContentReference): SafDocumentMetadata {
        val documentUri = document.toDocumentUri()
        return contentResolver
            .query(
                documentUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) throw FileNotFoundException("Document was not found")
                SafDocumentMetadata(
                    displayName = if (cursor.isNull(0)) null else cursor.getString(0),
                    mimeType = if (cursor.isNull(1)) null else cursor.getString(1),
                    sizeBytes =
                        if (cursor.isNull(2)) {
                            null
                        } else {
                            cursor.getLong(2).takeIf { it >= 0L }
                        },
                )
            } ?: throw FileNotFoundException("Provider returned no document metadata")
    }

    override fun listChildDisplayNames(parent: ContentReference): Set<String> {
        val parentUri = parent.toDocumentUri()
        val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(parent.toUri(), parentDocumentId)
        return contentResolver
            .query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        if (!cursor.isNull(0)) add(cursor.getString(0))
                    }
                }
            } ?: throw FileNotFoundException("Provider returned no child listing")
    }

    override fun deletePartialDestination(destination: ImporterOwnedDestination): Boolean =
        DocumentsContract.deleteDocument(contentResolver, destination.document.toDocumentUri())

    private fun createDocument(
        parent: ContentReference,
        mimeType: String,
        displayName: String,
    ): ContentReference {
        requireValidDisplayName(displayName)
        val created =
            DocumentsContract.createDocument(
                contentResolver,
                parent.toDocumentUri(),
                mimeType,
                displayName,
            ) ?: throw FileNotFoundException("Provider refused to create the document")
        return ContentReference(created.toString())
    }

    private fun canQueryChildren(
        treeUri: Uri,
        documentId: String,
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        return contentResolver
            .query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { true } ?: false
    }

    private fun ContentReference.toDocumentUri(): Uri {
        val uri = toUri()
        if (!DocumentsContract.isTreeUri(uri)) return uri
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        return DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
    }

    private fun ContentReference.toUri(): Uri = Uri.parse(value)

    private fun requireValidDisplayName(displayName: String) {
        require(
            displayName.isNotBlank() &&
                displayName == displayName.trim() &&
                '/' !in displayName &&
                '\u0000' !in displayName,
        ) { "Display name must be trimmed, nonblank, and contain no path separators" }
    }

    private fun inaccessibleTree() =
        TreeCapabilities(
            exists = false,
            isDirectory = false,
            readable = false,
            writable = false,
            supportsChildCreation = false,
        )
}
