package com.audioape.plugin.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.ByteArray

/** Headless, deterministic correctness suite for the AA-020 credential vault. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginCredentialVaultTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `store then read round-trips the secret for the same plugin`() {
        val vault = vault()

        store(vault, "plugin.one", "cred.a", "token-alpha")

        val result = vault.read("plugin.one", "cred.a")
        assertTrue(result is VaultSuccess)
        assertEquals("token-alpha", textOf((result as VaultSuccess).value!!))
    }

    @Test
    fun `read returns not found for missing plugin key and missing credential`() {
        val vault = vault()

        assertTrue(vault.read("plugin.one", "missing") is VaultNotFound)
        assertTrue(vault.read("missing.plugin", "cred") is VaultNotFound)
    }

    @Test
    fun `plugins cannot read each other's credentials`() {
        val vault = vault()
        store(vault, "plugin.one", "shared", "alpha-secret")
        store(vault, "plugin.two", "shared", "beta-secret")

        assertFalse(digest("plugin.one") == digest("plugin.two"))
        assertFalse(alias("plugin.one") == alias("plugin.two"))
        assertFalse(pluginFile(vaultDir(), "plugin.one").name == pluginFile(vaultDir(), "plugin.two").name)

        val readB = vault.read("plugin.one", "shared")
        assertTrue(readB is VaultSuccess)
        assertEquals("alpha-secret", textOf((readB as VaultSuccess).value!!))

        assertTrue(vault.read("plugin.one", "credential-of-b") is VaultNotFound)
    }

    @Test
    fun `cross-plugin alias collision is impossible by construction`() {
        store(vault(), "plugin.one", "credential", "one")
        store(vault(), "plugin.two", "different", "two")

        assertFalse(digest("plugin.one") == digest("plugin.two"))
        assertFalse(alias("plugin.one") == alias("plugin.two"))
        assertFalse(pluginFile(vaultDir(), "plugin.one").name == pluginFile(vaultDir(), "plugin.two").name)
    }

    @Test
    fun `overwrite replaces the old value`() {
        val vault = vault()
        store(vault, "plugin.one", "token", "old-value")
        store(vault, "plugin.one", "token", "new-value")

        val result = vault.read("plugin.one", "token")
        assertTrue(result is VaultSuccess)
        assertEquals("new-value", textOf((result as VaultSuccess).value!!))
    }

    @Test
    fun `delete removes one credential and leaves siblings intact`() {
        val vault = vault()
        store(vault, "plugin.one", "one", "a")
        store(vault, "plugin.one", "two", "b")

        assertTrue(vault.delete("plugin.one", "one") is VaultSuccess)
        assertTrue(vault.read("plugin.one", "one") is VaultNotFound)
        assertTrue(vault.read("plugin.one", "two") is VaultSuccess)
        assertTrue(vault.delete("plugin.one", "missing") is VaultSuccess)
    }

    @Test
    fun `clear all removes every credential and the keystore key`() {
        val vault = vault()
        val ring = fakeRingBinding()
        store(vault, "plugin.one", "one", "a")
        store(vault, "plugin.one", "two", "b")

        assertTrue(vault.clearAll("plugin.one") is VaultSuccess)
        assertTrue(vault.read("plugin.one", "one") is VaultNotFound)
        assertFalse(ring.hasKey(digest("plugin.one")))
        assertFalse(pluginFile(vaultDir(), "plugin.one").exists())
    }

    @Test
    fun `invalid plugin or credential ids are rejected as forbidden`() {
        val vault = vault()

        assertTrue(vault.read("", "cred") is VaultForbidden)
        assertTrue(vault.read("plugin/one", "cred") is VaultForbidden)
        assertTrue(vault.read("plugin.one", "") is VaultForbidden)
        assertTrue(vault.store("plugin.one", "bad/cred", "x".toByteArray(StandardCharsets.UTF_8)) is VaultForbidden)
    }

    @Test
    fun `corrupt ciphertext file is a loud io failure, not not-found and not silently healed`() {
        val vault = vault()
        store(vault, "plugin.one", "token", "secret-value")
        val file = pluginFile(vaultDir(), "plugin.one")
        val tampered = ByteArray(7)
        tampered[0] = 0x41
        tampered[1] = 0x41
        tampered[2] = 0x56
        tampered[3] = 0x41
        tampered[4] = 0x55
        tampered[5] = 0x4c
        tampered[6] = 0x54
        Files.write(file.toPath(), tampered)

        assertTrue(vault.read("plugin.one", "token") is VaultIoFailure)

        // Corruption is loud, so the host's documented recovery is: drop the
        // unrecoverable state (clearAll), re-consent, then re-store cleanly.
        assertTrue(vault.clearAll("plugin.one") is VaultSuccess)
        store(vault, "plugin.one", "token", "recovered")
        assertTrue(vault.read("plugin.one", "token") is VaultSuccess)
    }

    @Test
    fun `key invalidation returns typed key-invalidated and re-auth can re-store cleanly`() {
        val vault = vault()
        val ring = fakeRingBinding()
        store(vault, "plugin.one", "token", "before-invalidation")

        ring.invalidate(digest("plugin.one"))
        assertTrue(vault.read("plugin.one", "token") is VaultKeyInvalidated)
        assertTrue(vault.store("plugin.one", "token", "try-again".toByteArray(StandardCharsets.UTF_8)) is VaultKeyInvalidated)

        // Re-authentication: the user consents again, the host clears the dead state,
        // and the plugin stores a fresh token under a fresh key.
        assertTrue(vault.clearAll("plugin.one") is VaultKeyInvalidated)
        assertTrue(ring.isInvalidated(digest("plugin.one")))
        ring.recover(digest("plugin.one"))
        assertTrue(vault.store("plugin.one", "token", "post-reauth".toByteArray(StandardCharsets.UTF_8)) is VaultSuccess)
        assertTrue(vault.read("plugin.one", "token") is VaultSuccess)
    }

    @Test
    fun `ciphertext at rest never contains the plaintext bytes`() {
        val vault = vault()
        val secret = "super-secret-token-314159"
        store(vault, "plugin.one", "token", secret)

        val onDisk = Files.readAllBytes(pluginFile(vaultDir(), "plugin.one").toPath())
        assertFalse(containsBytes(onDisk, secret.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `secret bytes never appear in vault log output`() {
        val vault = vault()
        val secret = "ULTRA-SECRET-TOKEN-271828"
        val capturedOutput = ByteArrayOutputStream()

        PrintStream(capturedOutput).use { logFunnel ->
            val originalOut = System.out
            val originalErr = System.err
            System.setOut(logFunnel)
            System.setErr(logFunnel)
            try {
                // Exercise every path that a plugin host would drive.
                store(vault, "plugin.one", "token", secret)
                vault.read("plugin.one", "token")
                vault.delete("plugin.one", "token")
                vault.clearAll("plugin.one")
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        }

        // Nothing the vault wrote to any output stream may contain the secret
        // (rule 11: tokens never enter logs; the vault itself has no logging).
        val captured = String(capturedOutput.toByteArray(), StandardCharsets.UTF_8)
        assertFalse(captured.contains(secret))
        assertFalse(captured.isNotEmpty())
    }

    // --- helpers ---------------------------------------------------------------

    private fun textOf(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

    private fun vaultDir(): File = File(temporaryFolder.root, "vault")

    private val binding = FakeRingBinding()

    private fun fakeRingBinding(): FakeVaultKeyRing = binding.ring

    private fun vault(): PluginCredentialVault = PluginCredentialVault(vaultDir()) { scope -> binding.ring }

    private fun store(
        vault: PluginCredentialVault,
        plugin: String,
        credential: String,
        secret: String,
    ) {
        val result = vault.store(plugin, credential, secret.toByteArray(StandardCharsets.UTF_8))
        assertTrue("store failed: $result", result is VaultSuccess)
    }

    private fun digest(pluginId: String): String = VaultKeyRing.pluginDigest(pluginId)

    private fun alias(pluginId: String): String = "vault.${digest(pluginId)}"

    private fun pluginFile(
        dir: File,
        pluginId: String,
    ): File = File(dir, "vault.${digest(pluginId)}.bin")

    private fun containsBytes(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty()) return true
        for (index in 0..(haystack.size - needle.size)) {
            var found = true
            for (offset in 0 until needle.size) {
                if (haystack[index + offset] != needle[offset]) {
                    found = false
                }
            }
            if (found) return true
        }
        return false
    }

    private class FakeRingBinding {
        val ring = FakeVaultKeyRing()

        fun ringProvider(scope: VaultKeyRing.Scope): VaultKeyRing = ring
    }
}
