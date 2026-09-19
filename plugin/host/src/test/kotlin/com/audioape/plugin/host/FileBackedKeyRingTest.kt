package com.audioape.plugin.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** ADR-0008 fallback key ring: deterministic derivation, single master key, 0600 best-effort. */
class FileBackedKeyRingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun derivesStablePerPluginKeysFromOneMasterKey() {
        val dir = tmp.newFolder("vault")
        val ring = FileBackedKeyRing(dir)

        val digestA = VaultKeyRing.pluginDigest("org.example.a")
        val digestB = VaultKeyRing.pluginDigest("org.example.b")

        val keyA1 = ring.getOrCreatePluginKey(digestA)
        val keyA2 = ring.getOrCreatePluginKey(digestA)
        val keyB = ring.getOrCreatePluginKey(digestB)

        // Deterministic: same plugin -> same key, repeated calls identical (content compare).
        assertEquals("repeated derivation must be identical", keyA1.getEncoded().toList(), keyA2.getEncoded().toList())
        // Isolated: different plugin -> different derived key.
        assertTrue("plugin keys must differ across plugins", !keyA1.getEncoded().contentEquals(keyB.getEncoded()))

        // One master key file, 32 bytes, created on first use.
        val master = File(dir, FileBackedKeyRing.MASTER_KEY_FILE_NAME)
        assertTrue("master key file must be created", master.exists())
        assertEquals("master key must be 32 bytes", 32L, Files.readAllBytes(master.toPath()).size.toLong())
    }

    @Test
    fun masterKeySurvivesRestartAndExistingFileIsReused() {
        val dir = tmp.newFolder("vault2")
        val ring1 = FileBackedKeyRing(dir)
        val digest = VaultKeyRing.pluginDigest("org.example.c")
        val first = ring1.getOrCreatePluginKey(digest).getEncoded()

        val ring2 = FileBackedKeyRing(dir)
        val second = ring2.getOrCreatePluginKey(digest).getEncoded()

        assertTrue("existing master key must be reused across ring instances", first.contentEquals(second))
    }
}
