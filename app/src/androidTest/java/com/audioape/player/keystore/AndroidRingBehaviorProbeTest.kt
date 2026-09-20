package com.audioape.player.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.junit.Test
import java.security.KeyStore
import javax.crypto.KeyGenerator
import kotlin.math.abs

/**
 * Throwaway behavioral probe (AA-020c review): pins down what the Android 16 keystore
 * implementation ACTUALLY does for edge cases the ring semantics depend on. Prints to logcat
 * tag AARingProbe; results captured from `adb logcat -d`.
 */
class AndroidRingBehaviorProbeTest {
    @Test
    fun probeEdgeBehaviors() {
        val alias = "probe_${abs(kotlin.random.Random.nextInt())}"
        val fresh = { KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) } }

        // 1. containsAlias on a FRESH loaded instance (absent + present).
        val c1 =
            try {
                fresh().containsAlias(alias)
            } catch (e: Throwable) {
                "THROW:${e.javaClass.simpleName}"
            }
        Log.i(TAG, "fresh.containsAlias(absent)=$c1")

        // 2. create via generator.
        try {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            gen.generateKey()
            Log.i(TAG, "generateKey=OK")
        } catch (e: Throwable) {
            Log.i(TAG, "generateKey=THROW:${e.javaClass.simpleName}:${e.message}")
        }

        // 3. getKey on the PRESENT alias (fresh instance).
        val g1 =
            try {
                fresh().getKey(alias, null)?.let { "KEY:${it.algorithm}" } ?: "NULL"
            } catch (
                e: Throwable,
            ) {
                "THROW:${e.javaClass.simpleName}:${e.message}"
            }
        Log.i(TAG, "fresh.getKey(present)=$g1")

        // 4. deleteEntry on the PRESENT alias (fresh instance).
        val d1 =
            try {
                fresh().deleteEntry(alias)
                "OK"
            } catch (e: Throwable) {
                "THROW:${e.javaClass.simpleName}:${e.message}"
            }
        Log.i(TAG, "fresh.deleteEntry(present)=$d1")

        // 5. getKey after deletion (absent).
        val g2 =
            try {
                fresh().getKey(alias, null)?.let { "KEY:${it.algorithm}" } ?: "NULL"
            } catch (
                e: Throwable,
            ) {
                "THROW:${e.javaClass.simpleName}:${e.message}"
            }
        Log.i(TAG, "fresh.getKey(afterDelete)=$g2")

        // 6. deleteEntry on the now-ABSENT alias.
        val d2 =
            try {
                fresh().deleteEntry(alias)
                "OK"
            } catch (e: Throwable) {
                "THROW:${e.javaClass.simpleName}:${e.message}"
            }
        Log.i(TAG, "fresh.deleteEntry(absent)=$d2")
    }

    private companion object {
        const val TAG = "AARingProbe"
    }
}
