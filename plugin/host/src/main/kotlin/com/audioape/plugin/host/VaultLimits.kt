package com.audioape.plugin.host

import kotlin.ByteArray

/**
 * Bounded sizes and id rules for the credential vault (AA-020). Keeping every id and
 * secret length capped keeps the per-plugin file header fixed-size and the alias
 * derivation cheap; it also gives the permission sheet a bounded shape to display.
 */
object VaultLimits {
    /** Upper bound on a vaulted secret, matching the plugin-boundary sanity budget. */
    const val MAX_SECRET_BYTES = 64 * 1024

    /** Upper bound on a pluginId/credentialId, in UTF-8 bytes after encoding. */
    const val MAX_ID_UTF8_BYTES = 128

    /** AES-256 key size. */
    const val KEY_BYTES = 32

    /** GCM nonce size for this vault. */
    const val IV_BYTES = 12

    /** GCM tag length in bits (128-bit tag = 16 bytes). */
    const val TAG_BITS = 128

    /** Cipher-transformed size of one AES block; used to bound the file read window. */
    const val BLOCK_BYTES = 16
}
