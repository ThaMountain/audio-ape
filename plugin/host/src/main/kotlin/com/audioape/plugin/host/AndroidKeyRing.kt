package com.audioape.plugin.host

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * OS-backed key ring (AA-020c, supersedes the ADR-0008 fallback as the production ring).
 *
 * Implements the VERIFIED Android-Keystore sequence from ADR-0009 (reproduced on both the
 * dev36 emulator and Galaxy S25 Ultra, Android 16 / API 36):
 *
 *     KeyStore.getInstance("AndroidKeyStore")   // SINGLE-ARG provider, not getDefaultType()
 *     keyStore.load(null, null)                 // required init (empirically verified)
 *     KeyGenerator.getInstance(KEY_ALGORITHM_AES, "AndroidKeyStore")
 *       + KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT | PURPOSE_DECRYPT)
 *           .setKeySize(256).setBlockModes(GCM).setEncryptionPaddings(NONE)
 *           .setUserAuthenticationRequired(false)
 *     generator.init(spec); generator.generateKey()
 *     freshStore().getKey(alias, null)          // reads via a FRESH loaded instance
 *
 * Known trait (ADR-0009 §2): `containsAlias`/`getKey` on the FIRST instance throw
 * `KeyStoreException: Uninitialized keystore` even after `load`, so existence checks and
 * reads always go through a FRESH `getInstance` + `load(null,null)` instance.
 *
 * Keys are OS-custodied (android.security.keystore2), never exportable through this API.
 * Alias = `vault.<sha256(pluginId)>`, matching the existing per-plugin digest naming, so
 * cross-plugin isolation is preserved (distinct alias per plugin + GCM AAD on ciphertext).
 */
class AndroidKeyRing : VaultKeyRing {
    override fun getOrCreatePluginKey(pluginDigest: String): SecretKey {
        pluginKeyOrNull(pluginDigest)?.let { return it }
        return createPluginKey(pluginDigest)
    }

    override fun pluginKeyOrNull(pluginDigest: String): SecretKey? {
        val alias = aliasFor(pluginDigest)
        return try {
            freshStore().getKey(alias, null) as SecretKey?
        } catch (_: KeyStoreException) {
            null // alias does not exist (or OS custody refused) — treated as "not present"
        }
    }

    override fun removePluginKey(pluginDigest: String) {
        val alias = aliasFor(pluginDigest)
        try {
            freshStore().deleteEntry(alias)
        } catch (ignored: Exception) {
            // deleteEntry on a missing alias throws; removing an absent plugin key is a no-op.
        }
    }

    private fun createPluginKey(pluginDigest: String): SecretKey {
        val alias = aliasFor(pluginDigest)
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKey()
        return freshStore().getKey(alias, null) as SecretKey
            ?: error("generated OS key not visible for alias $alias")
    }

    private fun freshStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null, null) }

    private fun aliasFor(pluginDigest: String): String = "vault.$pluginDigest"

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
