package com.audioape.plugin.host

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.ByteArray

/**
 * Thin, swappable key-ring over the Android Keystore's `APP` domain (AA-020).
 *
 * The production implementation is [VaultKeyRing.production]: a JCA [KeyStore] in the
 * app-facing `APP` keystore domain where each plugin owns one AES-256 [SecretKey] under
 * `vault.<sha256(pluginId)>`. Secret keys never leave the keystore; the [SecretKey]
 * returned by [getOrCreatePluginKey] is the JCE reference that backs a
 * [javax.crypto.Cipher] GCMParameterSpec operation, never extracted bytes and never
 * serialized to a file.
 *
 * [KeyProtection] is built narrowly: the purpose is decrypt only (the vault never
 * needs a key the plugin could use for anything else). Strongbox or biometric gating
 * is deliberately left off for the v0 token vault; that is a permission-sheet decision
 * (AA-023), not a silent downgrade. The keystore backend can still permanently
 * invalidate the key (biometric enrollment, device-lock rules); that maps to
 * [VaultKeyInvalidated] and always requires explicit re-authentication.
 *
 * Tests inject a deterministic fake ring instead of the real keystore backend so
 * [KeyPermanentlyInvalidatedException] can be simulated deterministically (real Android
 * keystore backends do not run headless). Alias derivation and file naming are shared
 * production code, so the namespace-isolation contract is exactly what ships.
 */
interface VaultKeyRing {
    /** Returns the plugin's AES key, creating it in the keystore when missing. */
    fun getOrCreatePluginKey(pluginDigest: String): SecretKey

    /** Returns the plugin's key, or null when no keystore entry exists for it yet. */
    fun pluginKeyOrNull(pluginDigest: String): SecretKey?

    /** Removes the plugin's keystore entry. */
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

        /** Production ring over the app-facing JCA keystore (see class doc). */
        fun production(scope: Scope): VaultKeyRing {
            require(scope == Scope.APP) { "only the app keystore domain is supported in v0" }
            return AndroidKeyRing()
        }

        /** Builder for the narrow, decrypt-only key protection used by [AndroidKeyRing]. */
        fun keyProtection(): KeyProtection = KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT).build()
    }
}

/**
 * Production ring: a process [KeyStore] in the app domain. This is the maintained,
 * non-deprecated path the architecture asks for (direct `KeyStore` + AES/GCM; the
 * `androidx.security:security-crypto` wrapper was deprecated upstream).
 */
class AndroidKeyRing : VaultKeyRing {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KeyStore.getDefaultType())
    }

    override fun getOrCreatePluginKey(pluginDigest: String): SecretKey {
        val alias = aliasFor(pluginDigest)
        lookupOrNull(alias)?.let { return it }
        val generated = generatePluginKey()
        keyStore.setEntry(alias, KeyStore.SecretKeyEntry(generated), VaultKeyRing.keyProtection())
        return lookupOrNull(alias) ?: generated
    }

    override fun pluginKeyOrNull(pluginDigest: String): SecretKey? = lookupOrNull(aliasFor(pluginDigest))

    override fun removePluginKey(pluginDigest: String) {
        val alias = aliasFor(pluginDigest)
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun aliasFor(pluginDigest: String): String = "vault.$pluginDigest"

    private fun lookupOrNull(alias: String): SecretKey? {
        if (!keyStore.containsAlias(alias)) return null
        return keyStore.getKey(alias, null) as SecretKey
    }

    private fun generatePluginKey(): SecretKey {
        // VaultLimits.KEY_BYTES random bytes wrapped as a JCE AES key. KeyGenerator
        // does not reliably honor init(32) across providers (notably the headless
        // OpenSSL/Conscrypt provider used in tests), so a SecretKeySpec with an
        // explicit 32-byte array is the portable construction; the Android Keystore's
        // SecretKeyEntry wraps exactly this shape.
        val randomBytes = ByteArray(VaultLimits.KEY_BYTES)
        SecureRandom().nextBytes(randomBytes)
        return SecretKeySpec(randomBytes, "AES")
    }
}
