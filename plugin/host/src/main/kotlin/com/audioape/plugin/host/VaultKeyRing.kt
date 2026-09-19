package com.audioape.plugin.host

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Thin, swappable key-ring abstraction for the plugin vault (AA-020).
 *
 * The production implementation is [FileBackedKeyRing] (ADR-0008): the OS-backed Android
 * Keystore was probed on BOTH the emulator and the physical Galaxy S25 Ultra (Android 16 /
 * SDK 36) and every documented `AndroidKeyStore` creation path throws
 * "Uninitialized keystore"; `android.security.keystore.KeyStore` is absent from the runtime.
 * The file-backed ring is the recorded, consciously-weaker v0 fallback; `VaultKeyRing` stays
 * the seam so OS-custodied keys can slot back in without touching the vault.
 *
 * Tests inject a deterministic fake ring so [KeyPermanentlyInvalidatedException] can be
 * simulated deterministically; alias/file derivation and id validation are shared production
 * code, so the namespace-isolation contract is exactly what ships.
 */
interface VaultKeyRing {
    /** Returns the plugin's AES key, creating it when missing. */
    fun getOrCreatePluginKey(pluginDigest: String): javax.crypto.SecretKey

    /** Returns the plugin's key, or null when no key material exists for it yet. */
    fun pluginKeyOrNull(pluginDigest: String): javax.crypto.SecretKey?

    /** Removes the plugin's key material. */
    fun removePluginKey(pluginDigest: String)

    enum class Scope {
        /** The standard app-facing keystore domain keyed by app identity. */
        APP,
    }

    companion object {
        private const val ALIAS_PREFIX = "vault."

        /** A plugin/credential id is non-empty, control-free, and within a byte budget. */
        fun isValidPluginId(pluginId: String): Boolean = isCanonicalId(pluginId)

        fun isValidCredentialId(credentialId: String): Boolean = isCanonicalId(credentialId)

        private fun isCanonicalId(id: String): Boolean {
            if (id.isEmpty() || hasControlCharacter(id) || id.contains("/")) return false
            return id.toByteArray(StandardCharsets.UTF_8).size <= VaultLimits.MAX_ID_UTF8_BYTES
        }

        private fun hasControlCharacter(id: String): Boolean {
            for (ch in id) {
                if (ch.code <= 0x1F || ch.code == 0x7F) return true
            }
            return false
        }

        /** One-way, UTF-8-stable digest used to derive every alias and file name. */
        fun pluginDigest(pluginId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(StandardCharsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}
