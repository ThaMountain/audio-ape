package com.audioape.core.download

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Shared crypto helpers: streaming SHA-256 + bounded hex/byte conversions. One place, tested.
 */
object DownloadHashes {
    fun sha256(input: InputStream): String =
        sha256Hex(
            input.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
                digest.digest()
            },
        )

    fun sha256(bytes: ByteArray): String = sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Verifies the file's contents against a hex digest without loading it into memory. */
    fun fileMatchesSha256(
        file: File,
        expectedHex: String,
    ): Boolean = file.isFile && sha256(file.inputStream()) == expectedHex

    private fun sha256Hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }
}

/**
 * Archive name safety (AA-021): every archive member name is checked INDEPENDENTLY of the
 * manifest grammar. The manifest's safe charset already forbids separators, but defense in
 * depth requires rejecting any member that looks like traversal, absolute, drive-lettered, or
 * Windows-escaped before it is ever materialized.
 */
object ArchiveNameChecker {
    fun rejectReason(rawName: String): String? {
        if (rawName.isBlank()) return "blank member name"
        if (rawName.length > ArchiveSafetyLimits.MAX_NAME_LENGTH) return "member name too long"
        // Windows separators are always rejected regardless of host.
        if (rawName.contains('\\')) return "backslash separator in member name"
        if (rawName.startsWith('/')) return "absolute member name"
        if (rawName.startsWith("//")) return "absolute member name"
        // Drive letters (C:\..., C:/...).
        if (rawName.length >= 2 && rawName[0].isLetter() && rawName[1] == ':') return "drive-letter member name"
        // Path traversal via dot segments (./x, ../x, a/../x).
        val segments = rawName.split('/')
        if (segments.any { it == ".." }) return "path traversal ('..') in member name"
        if (segments.any { it == "." }) return "dot segment in member name"
        return null
    }
}

object ArchiveSafetyLimits {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_PART_COUNT = 512
    const val MAX_PART_BYTES = 4L shl 30 // 4 GiB per part
    const val MAX_TOTAL_BYTES = 16L shl 30 // 16 GiB per book
    const val MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + (8L shl 20) // archive cap = parts + manifest + zip overhead
    const val MAX_INFLATE_RATIO = 200L // zip-bomb guard per member
    const val MAX_NAME_LENGTH = 255
    val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]{1,$MAX_NAME_LENGTH}")
}
