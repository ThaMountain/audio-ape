package com.audioape.plugin.host

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.LinkedHashMap

/**
 * On-disk persistence for one plugin's ciphertext-only vault (AA-020).
 *
 * The binary layout is fixed (see [VaultFileFormat]); this file only owns the atomic
 * write/read mechanics: a same-directory temp file, an fsync, an atomic replace, and a
 * best-effort temp cleanup in `finally`. A crash mid-write therefore never leaves a
 * torn record — the previous file survives until the replace. Reading uses the
 * stdlib's `File.readBytes()` plus strict, bounds-checked parsing, so any corruption is
 * [VaultIoFailure], never a silent "missing".
 */
internal fun writeCiphertextFile(
    file: File,
    records: Map<String, CipherBlob>,
) {
    if (records.isEmpty()) {
        Files.deleteIfExists(file.toPath())
        return
    }
    val serialized = VaultFileFormat.serialize(records)
    val parent = file.parentFile
    parent.mkdirs()
    val temp = File(parent, "${file.name}.tmp")
    try {
        val output = FileOutputStream(temp)
        try {
            output.write(serialized)
            output.fd.sync()
        } finally {
            output.close()
        }
        Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp.toPath())
    }
}

/** Reads a ciphertext vault file; an absent file is an empty vault. */
internal fun readCiphertextFile(file: File): DeserializedVaultFile {
    if (!file.exists()) return DeserializedVaultFile(LinkedHashMap())
    return VaultFileFormat.deserialize(file.readBytes())
}
