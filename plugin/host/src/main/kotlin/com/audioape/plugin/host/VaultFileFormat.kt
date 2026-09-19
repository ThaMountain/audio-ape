package com.audioape.plugin.host

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import kotlin.ByteArray

/**
 * Versioned, ciphertext-only file format for one plugin's vault (AA-020).
 *
 * Layout, big-endian:
 * ```text
 * magic "AAVAULT" (7 bytes)
 * format version        u16
 * entry count           u16
 * per entry:
 *   credentialId length u16 | credentialId UTF-8
 *   iv                  12 bytes
 *   ciphertext length   u32 | ciphertext
 *   tag                 16 bytes
 * ```
 * [deserialize] is strict: wrong magic/version, over-bounds record counts or byte
 * lengths, duplicate ids, and trailing bytes are all rejected so any corruption or
 * tampering surfaces as a loud [VaultIoFailure]. No plaintext ever appears here — the
 * [CipherBlob] fields are the only payload.
 */
internal object VaultFileFormat {
    private val MAGIC = "AAVAULT"
    internal const val FORMAT_VERSION = 1
    private const val MAX_RECORDS = 4096

    fun serialize(records: Map<String, CipherBlob>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        writeU16(out, FORMAT_VERSION)
        writeU16(out, records.size)
        for ((credentialId, blob) in records) {
            val idBytes = credentialId.toByteArray(StandardCharsets.UTF_8)
            writeU16(out, idBytes.size)
            out.write(idBytes)
            out.write(blob.iv)
            writeU32(out, blob.ciphertext.size)
            out.write(blob.ciphertext)
            out.write(blob.tag)
        }
        return out.toByteArray()
    }

    fun deserialize(bytes: ByteArray): DeserializedVaultFile {
        require(bytes.size >= HEADER_BYTES) { "vault file is too short to be a header" }
        val reader = ByteArrayReader(bytes)
        require(String(readBytes(reader, MAGIC.length), StandardCharsets.US_ASCII) == MAGIC) {
            "vault file has the wrong magic"
        }
        require(readU16(reader) == FORMAT_VERSION) { "vault file has an unsupported format" }
        val count = readU16(reader)
        require(count <= MAX_RECORDS) { "vault file record count is out of bounds" }
        val records = LinkedHashMap<String, CipherBlob>()
        for (recordIndex in 0 until count) {
            val credentialId = String(readBytes(reader, readU16(reader)), StandardCharsets.UTF_8)
            require(credentialId.isNotBlank() && credentialId.length <= VaultLimits.MAX_ID_UTF8_BYTES) {
                "vault record id is out of bounds"
            }
            val iv = readBytes(reader, VaultLimits.IV_BYTES)
            val ciphertext = readBytes(reader, readU32(reader))
            require(
                ciphertext.isNotEmpty() &&
                    ciphertext.size <= VaultLimits.MAX_SECRET_BYTES + VaultLimits.BLOCK_BYTES,
            ) {
                "vault record ciphertext is out of bounds"
            }
            val tag = readBytes(reader, VaultLimits.TAG_BITS / 8)
            val previous = records.put(credentialId, CipherBlob(iv, ciphertext, tag))
            require(previous == null) { "vault file contains a duplicate credential id" }
        }
        require(reader.remaining == 0) { "vault file has trailing bytes" }
        return DeserializedVaultFile(records)
    }

    private val HEADER_BYTES = MAGIC.length + 2 + 2

    private fun writeU16(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        val bytes = ByteArray(2)
        bytes[0] = (value shr 8).toByte()
        bytes[1] = value.toByte()
        out.write(bytes)
    }

    private fun writeU32(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        val bytes = ByteArray(4)
        bytes[0] = (value shr 24).toByte()
        bytes[1] = (value shr 16).toByte()
        bytes[2] = (value shr 8).toByte()
        bytes[3] = value.toByte()
        out.write(bytes)
    }

    private fun readU16(reader: ByteArrayReader): Int {
        val bytes = readBytes(reader, 2)
        return ((bytes[0].toInt() and 0xFF) shr 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun readU32(reader: ByteArrayReader): Int {
        val bytes = readBytes(reader, 4)
        return (
            ((bytes[0].toInt() and 0xFF) shr 24) or
                ((bytes[1].toInt() and 0xFF) shr 16) or
                ((bytes[2].toInt() and 0xFF) shr 8) or
                (bytes[3].toInt() and 0xFF)
        )
    }

    private fun readBytes(
        reader: ByteArrayReader,
        count: Int,
    ): ByteArray {
        val read = reader.read(count)
        if (read.size != count) throw EOFException("vault file ended mid-record")
        return read
    }
}

data class DeserializedVaultFile(
    val records: MutableMap<String, CipherBlob>,
)

private class ByteArrayReader(
    private val source: ByteArray,
) {
    private var offset = 0

    fun read(count: Int): ByteArray {
        if (count < 0 || count > source.size - offset) return ByteArray(0)
        val result = source.copyOfRange(offset, offset + count)
        offset += count
        return result
    }

    val remaining: Int
        get() = source.size - offset
}
