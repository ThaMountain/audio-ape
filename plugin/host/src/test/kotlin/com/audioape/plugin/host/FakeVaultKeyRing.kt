package com.audioape.plugin.host

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.SecureRandom
import java.util.HashMap
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic in-memory [VaultKeyRing] for the AA-020 correctness suite.
 *
 * It stores real [SecretKey] handles (JCA, generated per plugin digest) so the vault's
 * AES/GCM ciphertext codec runs on the real production code path. Invalidation is a
 * per-digest flag: once set, every key access throws
 * [KeyPermanentlyInvalidatedException], which is exactly how the real Android keystore
 * backend reports a biometric/strongbox rule change. This is the whole point of the
 * seam — physical keystore backends cannot run headless, so deterministic tests
 * exercise the callers' handling instead of the backend itself.
 */
class FakeVaultKeyRing : VaultKeyRing {
    private val keys = HashMap<String, SecretKey>()
    private val invalidated = HashMap<String, Boolean>()

    override fun getOrCreatePluginKey(pluginDigest: String): SecretKey {
        throwIfInvalidated(pluginDigest)
        return keys.computeIfAbsent(pluginDigest) { generateKey() }
    }

    override fun pluginKeyOrNull(pluginDigest: String): SecretKey? {
        throwIfInvalidated(pluginDigest)
        return keys[pluginDigest]
    }

    override fun removePluginKey(pluginDigest: String) {
        throwIfInvalidated(pluginDigest)
        keys.remove(pluginDigest)
        invalidated.remove(pluginDigest)
    }

    /** Sets the keystore-invalidated state for one plugin (the invalidation seam). */
    fun invalidate(pluginDigest: String) {
        invalidated[pluginDigest] = true
    }

    /** Clears the invalidation flag (the host's "re-authentication succeeded" step). */
    fun recover(pluginDigest: String) {
        invalidated[pluginDigest] = false
    }

    fun isInvalidated(pluginDigest: String): Boolean = if (invalidated[pluginDigest] != null) invalidated[pluginDigest]!! else false

    fun hasKey(pluginDigest: String): Boolean = keys.containsKey(pluginDigest)

    private fun throwIfInvalidated(pluginDigest: String) {
        if (isInvalidated(pluginDigest)) {
            throw KeyPermanentlyInvalidatedException()
        }
    }

    private fun generateKey(): SecretKey {
        val randomBytes = ByteArray(VaultLimits.KEY_BYTES)
        SecureRandom().nextBytes(randomBytes)
        return SecretKeySpec(randomBytes, "AES")
    }
}
