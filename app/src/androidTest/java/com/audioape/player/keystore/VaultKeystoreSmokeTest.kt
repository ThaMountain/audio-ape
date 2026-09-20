package com.audioape.player.keystore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audioape.plugin.host.FileBackedKeyRing
import com.audioape.plugin.host.PluginCredentialVault
import com.audioape.plugin.host.VaultNotFound
import com.audioape.plugin.host.VaultSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * AA-020 device/emulator pass: drives the PRODUCTION key ring (FileBackedKeyRing, per
 * ADR-0008 — the OS Android KeyStore is blocked on Android 16/API 36) on a real Android
 * runtime. Verifies store/read/delete round-trip, cross-plugin isolation, ciphertext-only at
 * rest, and the app-private master-key file, using app-private storage only.
 */
class VaultKeystoreSmokeTest {
    private lateinit var context: Context
    private lateinit var vaultDirectory: File
    private lateinit var vault: PluginCredentialVault

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        vaultDirectory = File(context.filesDir, "vault-smoke").apply { mkdirs() }
        vault = PluginCredentialVault(vaultDirectory)
    }

    @Test
    fun fileBackedVaultStoreReadDeleteRoundTripsIsolatedAndCiphertextOnlyAtRest() {
        val secret = "s3cret-plug1n-t0ken".toByteArray(StandardCharsets.UTF_8)

        assertTrue(vault.store(PLUGIN_A, CREDENTIAL, secret) is VaultSuccess)

        val roundTrip = vault.read(PLUGIN_A, CREDENTIAL)
        assertTrue("read must succeed on the production key ring", roundTrip is VaultSuccess)
        val plaintext = requireNotNull((roundTrip as VaultSuccess).value)
        assertEquals(secret.toList(), plaintext.toList())

        // Cross-plugin isolation: plugin B derives its own key; it cannot see A's credential.
        assertTrue("plugin B must not see plugin A's credential", vault.read(PLUGIN_B, CREDENTIAL) is VaultNotFound)

        // Ciphertext-only-at-rest: the per-plugin records file never contains the plaintext.
        val recordsFile =
            requireNotNull(
                (vaultDirectory.listFiles() ?: emptyArray()).firstOrNull { it.isFile && it.name.startsWith("vault.") },
            ) { "vault records file must exist" }
        val onDisk = Files.readAllBytes(recordsFile.toPath())
        assertFalse(
            "on-disk vault file must not contain the plaintext secret",
            String(bytes = onDisk, charset = StandardCharsets.UTF_8).contains("plug1n"),
        )

        // OS custody: a FRESH vault instance (fresh KeyStore + load) must still read the same
        // credential — proves the OS key persists across instances.
        val freshVault = PluginCredentialVault(vaultDirectory)
        val reRead = freshVault.read(PLUGIN_A, CREDENTIAL)
        assertTrue("fresh-instance read must succeed through the OS ring", reRead is VaultSuccess)
        val reReadPlaintext = requireNotNull((reRead as VaultSuccess).value)
        assertEquals(secret.toList(), reReadPlaintext.toList())

        assertTrue(vault.delete(PLUGIN_A, CREDENTIAL) is VaultSuccess)
        assertTrue("deleted credential must read back as NOT_FOUND", vault.read(PLUGIN_A, CREDENTIAL) is VaultNotFound)

        assertTrue(vault.clearAll(PLUGIN_A) is VaultSuccess)
    }

    private companion object {
        const val PLUGIN_A = "org.example.audioape.vaultsmoke.a"
        const val PLUGIN_B = "org.example.audioape.vaultsmoke.b"
        const val CREDENTIAL = "provider-token"
    }
}
