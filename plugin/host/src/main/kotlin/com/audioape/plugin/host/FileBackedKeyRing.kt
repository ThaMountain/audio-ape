package com.audioape.plugin.host

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.ByteArray

/**
 * File-backed key ring: the safe fallback for AA-020 until the Android Keystore OS-backing is
 * usable on the target (ADR-0008 — every documented AndroidKeyStore path throws
 * "Uninitialized keystore" on Android 16 / API 36, on BOTH the emulator and the Galaxy S25 Ultra,
 * and `android.security.keystore.KeyStore` is absent from the runtime).
 *
 * Custody model (documented WEAKER than the intended Keystore-pinned keys):
 * - One app-private 256-bit master key generated ONCE (SecureRandom) at
 *   `{vaultDirectory}/vault-master.key`, file permissions 0600 where the FS honors them.
 * - Every plugin's AES key is deterministically derived as HMAC-SHA256(masterKey,
 *   pluginDigest) — no per-plugin key bytes are ever stored; cross-plugin isolation is
 *   preserved because each plugin gets a different derived key and the GCM AAD still binds
 *   ciphertext to the plugin's digest.
 * - The master key lives ONLY in app-private storage: never external/SAF storage, never
 *   serialized beside media, and already excluded from Android backup + device-to-device
 *   transfer by the AA-016 manifest rules (allowBackup=false + extraction exclusions).
 *
 * Honest downgrade vs the Keystore design: the key is extractable by any attacker who can
 * read app-private files on the device (no OS-level key custody). This is a recorded,
 * consciously-accepted v0 fallback (ADR-0008); re-evaluating OS-custodied keys is a follow-up
 * once the Android 16 Keystore app-surface question is resolved (or a maintained wrapper is
 * available). Key revocation → re-authentication is supported by the vault's existing
 * VaultKeyInvalidated flow (delete key + re-auth); file-backed keys don't expire on their own.
 */
class FileBackedKeyRing(
    private val vaultDirectory: File,
) : VaultKeyRing {
    override fun getOrCreatePluginKey(pluginDigest: String): SecretKey = derivedPluginKey(pluginDigest)

    override fun pluginKeyOrNull(pluginDigest: String): SecretKey? = derivedPluginKey(pluginDigest)

    override fun removePluginKey(pluginDigest: String) {
        // File-backed keys are deterministic derivations of the master; there is no per-plugin
        // entry to remove. The vault's clearAll deletes the master key file to achieve the same
        // wipe-and-re-auth semantics.
        require(vaultDirectory.exists()) { "vault directory must exist for key removal" }
    }

    private fun derivedPluginKey(pluginDigest: String): SecretKey {
        val master = loadOrCreateMasterKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        mac.update(pluginDigest.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val digest = ByteArray(VaultLimits.KEY_BYTES)
        mac.doFinal(digest, 0)
        return SecretKeySpec(digest, "AES")
    }

    private fun loadOrCreateMasterKey(): ByteArray {
        val keyFile = masterKeyFile()
        if (keyFile.exists()) {
            return Files.readAllBytes(keyFile.toPath())
        }
        val generated = ByteArray(VaultLimits.KEY_BYTES)
        SecureRandom().nextBytes(generated)
        Files.write(keyFile.toPath(), generated)
        try {
            val permissions = PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(keyFile.toPath(), permissions)
        } catch (ignore: UnsupportedOperationException) {
            // FAT/exFAT/legacy filesystems do not support POSIX perms; best-effort only.
        }
        return generated
    }

    private fun masterKeyFile(): File = File(vaultDirectory, MASTER_KEY_FILE_NAME)

    companion object {
        /** Fixed file name of the single app-private master key ("vault-master.key"). */
        const val MASTER_KEY_FILE_NAME = "vault-master.key"
    }
}
