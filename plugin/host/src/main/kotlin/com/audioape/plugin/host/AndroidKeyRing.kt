package com.audioape.plugin.host

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
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
 * Error semantics (strict; established empirically with AndroidRingBehaviorProbeTest on
 * Android 16): an ABSENT alias is reported as `getKey` returning null — never as an exception;
 * `deleteEntry` on an absent alias is a silent no-op. Therefore a `KeyStoreException`
 * (or any other throwable) out of these calls is a REAL keystore failure and is propagated —
 * it must NOT be reported as "key missing", or the vault could silently mint a replacement
 * key (invalidating existing ciphertext) or report revocation success that didn't happen.
 * [PluginCredentialVault] maps propagated failures to a typed [VaultIoFailure].
 */
class AndroidKeyRing : VaultKeyRing {
    override fun getOrCreatePluginKey(pluginDigest: String): SecretKey {
        pluginKeyOrNull(pluginDigest)?.let { return it }
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

    override fun pluginKeyOrNull(pluginDigest: String): SecretKey? {
        // Absent alias -> null (verified behavior, never an exception). Anything else
        // (KeyStoreException, ClassCastException, ...) is a real failure: propagate.
        return freshStore().getKey(aliasFor(pluginDigest), null) as SecretKey?
    }

    override fun removePluginKey(pluginDigest: String) {
        val alias = aliasFor(pluginDigest)
        // Absent alias is a no-op (verified); failures propagate.
        freshStore().deleteEntry(alias)
        // Revocation is only reported as done once the key is provably gone.
        if (freshStore().getKey(alias, null) != null) {
            error("OS key $alias still present after deleteEntry")
        }
    }

    private fun freshStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null, null) }

    private fun aliasFor(pluginDigest: String): String = "vault.$pluginDigest"

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
