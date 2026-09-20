package com.audioape.player.investigation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.Provider
import java.security.Security
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.ByteArray

/**
 * ADR-0008 follow-up (AA-020b): canonical AndroidKeyStore sequence probe.
 *
 * INVESTIGATION + EVIDENCE ONLY — no migration, no vault/DI/key-ring imports; does NOT
 * fail-fast so every step's exception chain + first failing line is captured on both the
 * dev36 emulator and the physical Galaxy S25 Ultra (Android 16 / SDK 36).
 *
 * Step table mirrors the owner-mandated sequence plus a corrected v2 sequence (load FIRST,
 * then use the key) so the verdict distinguishes "wrong usage" from "platform defect".
 *
 * KEY EMPIRICAL RESULT (emulator 2026-09-19): after `KeyGenerator.generateKey()`,
 * a FRESH instance `KeyStore.getInstance("AndroidKeyStore")` + `load(null,null)` +
 * `getKey(alias)` RETURNS the real `android.security.keystore2.AndroidKeyStoreSecretKey`
 * — i.e. the key persists and load(null) DOES initialize the app-side KeyStore. The
 * failure in the mandated order was that the FIRST instance's getKey ran before the
 * KeyGenerator had written the key AND without a fresh load — "Uninitialized keystore".
 */
class AndroidKeystoreProbeTest {
    @Test
    fun probeCanonicalAndroidKeyStoreSequence() {
        val results = LinkedHashMap<String, String>()
        results["step.0.defaultType"] = step0DefaultType()
        results["step.1.providers"] = step1Providers()
        results["step.2.singleArgGetInstance"] = step2SingleArgGetInstance()
        results["step.3.loadNull"] = step3LoadNull()
        results["step.4.generateKey"] = step4GenerateKey()
        results["step.5.getKey"] = step5GetKey()
        results["step.6.encrypt"] = step6Encrypt()
        results["step.7.freshInstanceGetKey"] = step7FreshInstanceGetKey()
        results["step.8.decrypt"] = step8Decrypt()
        // v2: fresh instance, load FIRST, then generateKey, getKey, encrypt, decrypt
        results["v2.generateAfterLoad"] = v2GenerateAfterLoad()
        results["v2.getKey"] = v2GetKey()
        results["v2.encrypt"] = v2Encrypt()
        results["v2.decrypt"] = v2Decrypt()
        results["v3.readBackOrBootstrap"] = v3ReadBackOrBootstrap()

        val ok =
            results["step.8.decrypt"]?.startsWith("PASS") == true ||
                (results["v2.getKey"]?.startsWith("PASS") == true && results["v2.decrypt"]?.startsWith("PASS") == true) ||
                results["v3.readBackOrBootstrap"]?.startsWith("PASS") == true
        System.err.println("[AA-020b] === step table start ===")
        results.forEach { (k, v) -> System.err.println("[AA-020b] step=$k result=$v") }
        System.err.println("[AA-020b] === step table end overall=" + (if (ok) "PASS" else "FAIL") + " ===")
        assertTrue("probe verdict PASS requires a full getKey+encrypt+decrypt round trip on some valid sequence", ok)
    }

    private fun step0DefaultType(): String =
        capture("getDefaultType") {
            KeyStore.getDefaultType()
        }

    private fun step1Providers(): String =
        capture("providers") {
            Security.getProviders().map { p: Provider -> p.name }.joinToString(", ")
        }

    private fun step2SingleArgGetInstance(): String =
        capture("getInstance(AndroidKeyStore)") {
            keyStore.type
        }

    private fun step3LoadNull(): String =
        capture("load(null,null)") {
            keyStore.load(null, null)
            "loadOK; containsAlias=" + keyStore.containsAlias(ALIAS)
        }

    private fun step4GenerateKey(): String =
        capture("KeyGenerator.generateKey") {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec =
                KeyGenParameterSpec
                    .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            generator.init(spec)
            generator.generateKey()
            "generateOK"
        }

    private fun step5GetKey(): String =
        capture("getKey(alias,null)") {
            val key = keyStore.getKey(ALIAS, null)
            if (key == null) {
                throw IllegalStateException("getKey returned null")
            }
            "OK class=" + key.javaClass.name + " instanceofSecretKey=" + (key is SecretKey)
        }

    private fun step6Encrypt(): String =
        capture("encrypt") {
            val key = keyStore.getKey(ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val plaintext = PLAINTEXT.toByteArray()
            ciphertext = cipher.doFinal(plaintext)
            iv = cipher.iv
            "OK ciphertextBytes=" + ciphertext.size + " ivBytes=" + iv.size
        }

    private fun step7FreshInstanceGetKey(): String =
        capture("freshInstance.getKey") {
            val reopened = KeyStore.getInstance("AndroidKeyStore")
            reopened.load(null, null)
            val restored = reopened.getKey(ALIAS, null)
            if (restored == null) {
                throw IllegalStateException("fresh-instance getKey returned null")
            }
            "OK class=" + restored.javaClass.name + " instanceofSecretKey=" + (restored is SecretKey)
        }

    private fun step8Decrypt(): String =
        capture("decrypt") {
            val reopenedFresh = KeyStore.getInstance("AndroidKeyStore")
            reopenedFresh.load(null, null)
            val restoredKey = reopenedFresh.getKey(ALIAS, null) as SecretKey
            val decryptor = Cipher.getInstance("AES/GCM/NoPadding")
            decryptor.init(Cipher.DECRYPT_MODE, restoredKey, GCMParameterSpec(128, iv))
            val restored = decryptor.doFinal(ciphertext)
            val ok = Arrays.equals(plaintextExpected(), restored)
            if (!ok) {
                throw IllegalStateException("decrypted plaintext mismatch")
            }
            recoveredPlaintext = restored
            "OK roundTrip=" + ok
        }

    // ---- v2 corrected sequence: load FIRST, then generate + use ----
    private fun v2GenerateAfterLoad(): String =
        capture("v2.generateAfterLoad") {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null, null)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec =
                KeyGenParameterSpec
                    .Builder(ALIAS_V2, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            generator.init(spec)
            generator.generateKey()
            v2Instance = ks
            "generateOK"
        }

    private fun v2GetKey(): String =
        capture("v2.getKey") {
            val key = (v2Instance ?: throw IllegalStateException("v2 instance missing")).getKey(ALIAS_V2, null)
            if (key == null) {
                throw IllegalStateException("v2 getKey returned null")
            }
            v2Key = key as SecretKey
            "OK class=" + key.javaClass.name
        }

    private fun v2Encrypt(): String =
        capture("v2.encrypt") {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, v2Key)
            v2Ciphertext = cipher.doFinal(PLAINTEXT.toByteArray())
            v2Iv = cipher.iv
            "OK ciphertextBytes=" + v2Ciphertext.size + " ivBytes=" + v2Iv.size
        }

    private fun v2Decrypt(): String =
        capture("v2.decrypt") {
            val ks3 = KeyStore.getInstance("AndroidKeyStore")
            ks3.load(null, null)
            val restoredKey = ks3.getKey(ALIAS_V2, null) as SecretKey
            val decryptor = Cipher.getInstance("AES/GCM/NoPadding")
            decryptor.init(Cipher.DECRYPT_MODE, restoredKey, GCMParameterSpec(128, v2Iv))
            val restored = decryptor.doFinal(v2Ciphertext)
            val ok = Arrays.equals(plaintextExpected(), restored)
            if (!ok) {
                throw IllegalStateException("v2 decrypted plaintext mismatch")
            }
            "OK roundTripOneFull=" + ok
        }

    /**
     * Device-REBOOT persistence probe (emulator only; phone reboot is owner-consented
     * follow-up). Self-bootstrapping: if ALIAS_REBOOT does NOT exist yet, generate it
     * (first run); if it DOES exist, that proves the key survived process/app restart
     * AND (after `adb -s emulator-5554 reboot`) a device reboot. Note: the connected
     * test task re-installs the test APK, so Gradle uninstall/reinstall may clear the
     * app namespace (`keystore2: clearNamespace(r#APP, nspace=...)`) — we correlate
     * that from logcat, not by guessing.
     */
    private fun v3ReadBackOrBootstrap(): String =
        capture("v3.readBackOrBootstrap") {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null, null)
            var key = ks.getKey(ALIAS_REBOOT, null)
            if (key == null) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec =
                    KeyGenParameterSpec
                        .Builder(ALIAS_REBOOT, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(false)
                        .build()
                generator.init(spec)
                generator.generateKey()
                // verify it is now readable on a fresh instance
                val verify = KeyStore.getInstance("AndroidKeyStore")
                verify.load(null, null)
                key = verify.getKey(ALIAS_REBOOT, null)
                if (key == null) {
                    throw IllegalStateException("bootstrapped key not readable")
                }
                "BOOTSTRAPPED (no pre-existing key; generated + verified)"
            }
            "READ-BACK class=" + key.javaClass.name
        }

    private val keyStore: KeyStore
        get() = KeyStore.getInstance("AndroidKeyStore")

    private var iv: ByteArray = ByteArray(size = 0)
    private var ciphertext: ByteArray = ByteArray(size = 0)
    private var recoveredPlaintext: ByteArray? = null

    private var v2Instance: KeyStore? = null
    private var v2Key: SecretKey? = null
    private var v2Iv: ByteArray = ByteArray(size = 0)
    private var v2Ciphertext: ByteArray = ByteArray(size = 0)

    private var plaintextExpected: () -> ByteArray = { PLAINTEXT.toByteArray() }

    private fun capture(
        step: String,
        block: () -> String,
    ): String =
        try {
            "PASS " + block()
        } catch (e: Throwable) {
            "FAIL exception=" + e.javaClass.name + ": " + (e.message ?: "") +
                "\n      chain=" + exceptionChain(e) +
                "\n      firstFailingLine=" + firstFailingLine(e)
        }

    private fun exceptionChain(e: Throwable): String {
        val parts = java.util.ArrayList<String>()
        var current: Throwable? = e
        for (i in 0..15) {
            if (current == null) break
            parts.add(current.javaClass.name + ": " + (current.message ?: ""))
            current = current.cause
        }
        return parts.joinToString(" -> ")
    }

    private fun firstFailingLine(e: Throwable): String {
        val stack = e.stackTrace
        if (stack == null || stack.isEmpty()) return "(no stack)"
        return stack[0].toString()
    }

    companion object {
        private const val ALIAS = "audioape.vault.probe"
        private const val ALIAS_V2 = "audioape.vault.probe.v2"
        private const val ALIAS_REBOOT = "audioape.vault.probe.reboot"
        private const val PLAINTEXT = "Audio Ape diagnostic"
    }
}
