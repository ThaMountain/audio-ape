package com.audioape.plugin.host

import java.nio.charset.StandardCharsets
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.ByteArray

/**
 * One AES-256/GCM ciphertext record (AA-020). The 12-byte random IV is generated per
 * store; the GCM authenticator is the trailing tag of the cipher output and covers
 * both ciphertext and the plugin digest marker (passed as AAD), so a blob written for
 * one plugin can never be replayed inside another plugin's file without failing the
 * tag check first. Android 36's JCA returns `ciphertext || tag` from [Cipher.doFinal]
 * in GCM mode; [splitTagged] splits it back.
 */
data class CipherBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray,
) {
    init {
        require(iv.size == VaultLimits.IV_BYTES) { "vault blob iv has a fixed size" }
        require(ciphertext.isNotEmpty()) { "vault blob ciphertext must not be empty" }
        require(tag.size == VaultLimits.TAG_BITS / 8) { "vault blob tag has a fixed size" }
        require(ciphertext.size <= VaultLimits.MAX_SECRET_BYTES + VaultLimits.BLOCK_BYTES) {
            "vault blob ciphertext is bounded by the secret size limit"
        }
    }

    companion object {
        /**
         * Encrypts [plaintext] under [key]; the plugin digest becomes GCM AAD. The IV is
         * provider-generated (AA-020c): Android Keystore-custodied keys REJECT caller-provided
         * IVs on ENCRYPT ("Caller-provided IV not permitted"), so we init without an IV and
         * store the IV the provider used ([Cipher.iv]).
         */
        fun encrypt(
            key: SecretKey,
            plaintext: ByteArray,
            pluginDigest: String,
        ): CipherBlob {
            val (iv, tagged) = seal(Cipher.ENCRYPT_MODE, key, null, plaintext, pluginDigest)
            return splitTagged(iv, tagged)
        }

        /**
         * Decrypts and authenticates one blob. Returns the plaintext, or null on any
         * integrity failure (wrong key, tampered tag, truncated ciphertext). The caller
         * frames null as [VaultIoFailure], never as "not found".
         */
        fun decrypt(
            blob: CipherBlob,
            key: SecretKey,
            pluginDigest: String,
        ): ByteArray? {
            // GCM decryption expects `ciphertext || tag` as the single input; doFinal
            // verifies the authenticator and throws on any mismatch.
            val tagged = ByteArray(blob.ciphertext.size + blob.tag.size)
            System.arraycopy(blob.ciphertext, 0, tagged, 0, blob.ciphertext.size)
            System.arraycopy(blob.tag, 0, tagged, blob.ciphertext.size, blob.tag.size)
            return try {
                val (_, tagged) = seal(Cipher.DECRYPT_MODE, key, blob.iv, tagged, pluginDigest)
                tagged
            } catch (ignored: Exception) {
                null
            }
        }

        /** Splits the GCM `ciphertext || tag` output that JCA doFinal returns. */
        private fun splitTagged(
            iv: ByteArray,
            taggedOutput: ByteArray,
        ): CipherBlob {
            val tagSize = VaultLimits.TAG_BITS / 8
            require(taggedOutput.size >= tagSize) { "GCM output must carry its tag" }
            return CipherBlob(
                iv = iv,
                ciphertext = taggedOutput.copyOfRange(0, taggedOutput.size - tagSize),
                tag = taggedOutput.copyOfRange(taggedOutput.size - tagSize, taggedOutput.size),
            )
        }

        private fun seal(
            mode: Int,
            key: SecretKey,
            iv: ByteArray?,
            input: ByteArray,
            pluginDigest: String,
        ): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (iv == null) {
                // Provider generates the IV (mandatory for Android Keystore keys on ENCRYPT).
                cipher.init(mode, key)
            } else {
                cipher.init(mode, key, GCMParameterSpec(VaultLimits.TAG_BITS, iv))
            }
            cipher.updateAAD(pluginDigest.toByteArray(StandardCharsets.UTF_8))
            // Decryption: JCA verifies the authenticator and throws on mismatch (caller IV
            // permitted); encryption: returned bytes are `ciphertext || tag`, and [Cipher.iv]
            // holds the provider-generated IV to persist. first = used IV, second = output.
            return cipher.iv to cipher.doFinal(input)
        }
    }
}
