package com.audioape.core.storage

/** Best-effort defense for roots Android 11+ normally disables in the system picker. */
object UnsupportedTreeRootPolicy {
    private const val EXTERNAL_STORAGE_AUTHORITY =
        "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    fun isUnsupported(descriptor: TreeDescriptor): Boolean {
        val authority = descriptor.authority.lowercase()
        val documentId = descriptor.documentId.trim().trimEnd('/')

        if (authority == DOWNLOADS_AUTHORITY) {
            return documentId.equals("downloads", ignoreCase = true) ||
                documentId.equals("downloads:", ignoreCase = true)
        }
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return false

        val separator = documentId.indexOf(':')
        if (separator < 0) return false
        val relativePath = documentId.substring(separator + 1).trim('/')
        if (relativePath.isEmpty()) return true

        return relativePath.equals("Download", ignoreCase = true) ||
            relativePath.equals("Downloads", ignoreCase = true)
    }
}
