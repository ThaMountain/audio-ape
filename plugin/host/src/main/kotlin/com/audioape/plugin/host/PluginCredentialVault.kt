package com.audioape.plugin.host

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.nio.file.Files
import kotlin.ByteArray

/**
 * Host-private encrypted credential store for typed plugin access (AA-020, §F Vault).
 *
 * ## Trust model
 *
 * - The key material lives in the Android Keystore as one AES-256 secret-key entry per
 *   plugin (per-plugin keys). Keys never leave the Keystore; the JCA [javax.crypto.SecretKey]
 *   handle is a reference that only ever backs a [javax.crypto.Cipher] GCMParameterSpec
 *   operation, and the raw key bytes are never serialized or logged. Per-plugin keys were
 *   chosen over a single master key: plugin scale is tiny, every plugin gets full
 *   key-lifecycle isolation (a plugin's revocation or invalidation can never affect
 *   another plugin's ciphertext), alias derivation is one prefix plus one UTF-8-safe
 *   SHA-256 step per id, and there is no key-wrapping round trip to maintain. Keys live
 *   in the `APP` keystore domain (the app-facing `KeyStore.setEntry`/`getKey` code path
 *   the Android documentation describes), which is what "host-private" means at the app
 *   level: plugin code never obtains a keystore reference, so it cannot enumerate or
 *   read another plugin's alias.
 * - Only ciphertext is ever at rest, in one app-private per-plugin file under
 *   [vaultDirectory] (see [VaultFileFormat]). Nothing here ever writes to external
 *   storage (SAF), and the file carries no plaintext. The app's manifest
 *   (allowBackup="false" plus backup/transfer extraction exclusions) already covers
 *   this directory; see the AA-016 backup contract test.
 * - Cross-plugin access is impossible by construction: [VaultKeyScoped] derives both
 *   the keystore alias and the on-disk file name from the same SHA-256 digest of the
 *   plugin id, so a plugin cannot name an alias that resolves to another plugin's
 *   scope; each record's GCM AAD additionally binds the digest, and [read] verifies it.
 *
 * ## Key invalidation
 *
 * The Keystore can permanently invalidate a key (strongbox/biometric rule change,
 * backend reset). On [KeyPermanentlyInvalidatedException] every read path returns
 * [VaultKeyInvalidated], and the host never re-creates or re-decrypts. Re-authentication
 * is the caller's explicit job: re-run the plugin authorization flow, then
 * [clearAll] may be called to drop unrecoverable state (see [VaultKeyInvalidated]).
 *
 * ## Thread model and testing seam
 *
 * All operations are synchronous (the app already routes plugin operations off the UI
 * thread). The only seam is [VaultKeyRing]: tests inject a deterministic fake ring and
 * can simulate invalidation; the production ring is [FileBackedKeyRing] (ADR-0008 — the
 * OS Android Keystore is blocked on Android 16/API 36). The AES/GCM ciphertext at rest is
 * produced by the same production [javax.crypto.Cipher] code path in tests, so the on-disk
 * ciphertext assertion is real.
 */
class PluginCredentialVault(
    private val vaultDirectory: java.io.File,
    private val keystoreProvider: (scope: VaultKeyRing.Scope) -> VaultKeyRing = { FileBackedKeyRing(vaultDirectory) },
) {
    /** Creates (or opens) a keystore key for [pluginId] and stores [secret] under [credentialId]. */
    fun store(
        pluginId: String,
        credentialId: String,
        secret: ByteArray,
    ): VaultResult {
        try {
            val key = checkValidScope(pluginId, credentialId) ?: return VaultForbidden
            val ring = keystoreProvider(VaultKeyRing.Scope.APP)
            val pluginKey = ring.getOrCreatePluginKey(key.pluginDigest)
            val records = readCiphertextFile(key.pluginFile).records
            records.put(credentialId, CipherBlob.encrypt(pluginKey, secret, key.pluginDigest))
            writeCiphertextFile(key.pluginFile, records)
            // [secret] is the caller's owned copy; it is returned as the success value.
            // Only ciphertext reaches the file (see [CipherBlob]).
            return VaultSuccess(secret)
        } catch (keystoreInvalidated: KeyPermanentlyInvalidatedException) {
            return VaultKeyInvalidated
        } catch (ignored: Exception) {
            return VaultIoFailure
        }
    }

    /** Returns the plaintext for (pluginId, credentialId), or a typed failure. */
    fun read(
        pluginId: String,
        credentialId: String,
    ): VaultResult {
        try {
            val key = checkValidScope(pluginId, credentialId) ?: return VaultForbidden
            val ring = keystoreProvider(VaultKeyRing.Scope.APP)
            val pluginKey = ring.pluginKeyOrNull(key.pluginDigest) ?: return VaultNotFound
            val blob = readCiphertextFile(key.pluginFile).records[credentialId] ?: return VaultNotFound
            val plaintext = CipherBlob.decrypt(blob, pluginKey, key.pluginDigest) ?: return VaultIoFailure
            return VaultSuccess(plaintext)
        } catch (keystoreInvalidated: KeyPermanentlyInvalidatedException) {
            return VaultKeyInvalidated
        } catch (ignored: Exception) {
            return VaultIoFailure
        }
    }

    /** Removes one credential for [pluginId]. Missing entries are not an error. */
    fun delete(
        pluginId: String,
        credentialId: String,
    ): VaultResult {
        try {
            val key = checkValidScope(pluginId, credentialId) ?: return VaultForbidden
            val file = key.pluginFile
            if (!file.exists()) return VaultSuccess(null)
            val records = readCiphertextFile(file).records
            if (records.remove(credentialId) == null) return VaultSuccess(null)
            writeCiphertextFile(file, records)
            return VaultSuccess(null)
        } catch (keystoreInvalidated: KeyPermanentlyInvalidatedException) {
            return VaultKeyInvalidated
        } catch (ignored: Exception) {
            return VaultIoFailure
        }
    }

    /** Removes all credentials and the keystore key for [pluginId]. */
    fun clearAll(pluginId: String): VaultResult {
        try {
            val key = checkValidScope(pluginId, null) ?: return VaultForbidden
            val ring = keystoreProvider(VaultKeyRing.Scope.APP)
            ring.removePluginKey(key.pluginDigest)
            Files.deleteIfExists(key.pluginFile.toPath())
            return VaultSuccess(null)
        } catch (keystoreInvalidated: KeyPermanentlyInvalidatedException) {
            return VaultKeyInvalidated
        } catch (ignored: Exception) {
            return VaultIoFailure
        }
    }

    private fun checkValidScope(
        pluginId: String,
        credentialId: String?,
    ): VaultKeyScoped? {
        if (!VaultKeyRing.isValidPluginId(pluginId) ||
            (credentialId != null && !VaultKeyRing.isValidCredentialId(credentialId))
        ) {
            return null
        }
        return VaultKeyScoped(pluginId, credentialId, vaultDirectory)
    }
}
