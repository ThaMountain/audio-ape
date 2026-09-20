package com.audioape.player.keystore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audioape.plugin.host.AndroidKeyRing
import com.audioape.plugin.host.CipherBlob
import com.audioape.plugin.host.FileBackedKeyRing
import com.audioape.plugin.host.PluginCredentialVault
import com.audioape.plugin.host.VaultKeyMigrator
import com.audioape.plugin.host.VaultKeyRing
import com.audioape.plugin.host.VaultSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * AA-020c on-device proof of the OS-custodied AndroidKeyRing (ADR-0009 verified sequence) and
 * the FileBackedKeyRing → OS migration, running on a real Android runtime (emulator first).
 */
class AndroidKeyRingDeviceTest {
    private lateinit var context: Context
    private lateinit var vaultDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        vaultDirectory = File(context.filesDir, "vault-os").apply { mkdirs() }
    }

    @Test
    fun osRingCreatesReadsEncryptsAndPersistsAcrossFreshInstances() {
        val digest = VaultKeyRing.pluginDigest("org.example.osprobe")

        val firstRing = AndroidKeyRing()
        val key = firstRing.getOrCreatePluginKey(digest)

        val secret = "os-custodied-secret".toByteArray(StandardCharsets.UTF_8)
        val blob = CipherBlob.encrypt(key, secret, digest)

        // A SECOND ring instance (fresh KeyStore) must return the SAME OS key and decrypt the blob.
        val secondRing = AndroidKeyRing()
        val sameKey = requireNotNull(secondRing.pluginKeyOrNull(digest))
        val roundTrip = requireNotNull(CipherBlob.decrypt(blob, sameKey, digest))
        assertEquals(secret.toList(), roundTrip.toList())
    }

    @Test
    fun osRingRevocationInvalidatesCiphertext() {
        val digest = VaultKeyRing.pluginDigest("org.example.revoke")
        val ring = AndroidKeyRing()
        val key = ring.getOrCreatePluginKey(digest)
        val secret = "revocable".toByteArray(StandardCharsets.UTF_8)
        val blob = CipherBlob.encrypt(key, secret, digest)
        assertTrue(CipherBlob.decrypt(blob, ring.getOrCreatePluginKey(digest), digest) != null)

        ring.removePluginKey(digest)

        // After deleteEntry, a fresh lookup must report the key gone.
        assertNull("deleted OS key must be absent on a fresh instance", AndroidKeyRing().pluginKeyOrNull(digest))
    }

    @Test
    fun measuresOsKeyGetLatency() {
        val digest = VaultKeyRing.pluginDigest("org.example.bench")
        val warm = AndroidKeyRing().getOrCreatePluginKey(digest)
        requireNotNull(warm)
        val times =
            (1..25).map {
                val t0 = System.nanoTime()
                val k = AndroidKeyRing().pluginKeyOrNull(digest)
                requireNotNull(k)
                (System.nanoTime() - t0) / 1_000_000.0
            }
        val sorted = times.sorted()
        val avg = sorted.average()
        val median = sorted[12]
        android.util.Log.i(
            "AA020cBench",
            "getKey+load(ms) avg=$avg median=$median p95=${sorted[23]} all=[${times.joinToString(",") { "%.1f".format(it) }}]",
        )
        assertTrue("OS key lookup must be feasible on-device (avg < 250ms)", avg < 250.0)
    }

    @Test
    fun migratesFileBackedVaultToOsRingWithoutLoss() {
        // Seed through the PUBLIC vault API with the legacy (file-backed) ring.
        val legacyVault = PluginCredentialVault(vaultDirectory) { FileBackedKeyRing(vaultDirectory) }
        val pluginA = "org.example.migrate.a"
        val pluginB = "org.example.migrate.b"
        val secretA = "migrated-token-a".toByteArray(StandardCharsets.UTF_8)
        val secretB = "migrated-token-b".toByteArray(StandardCharsets.UTF_8)
        assertTrue(legacyVault.store(pluginA, "tok", secretA) is VaultSuccess)
        assertTrue(legacyVault.store(pluginA, "refresh", "migrated-refresh-a".toByteArray(StandardCharsets.UTF_8)) is VaultSuccess)
        assertTrue(legacyVault.store(pluginB, "tok", secretB) is VaultSuccess)
        assertTrue("legacy master key must exist after seeding", File(vaultDirectory, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())

        val report = VaultKeyMigrator(vaultDirectory, FileBackedKeyRing(vaultDirectory), AndroidKeyRing()).migrateAll()

        assertTrue("migration must succeed on-device: $report", report.succeeded)
        assertEquals(2, report.migratedFiles)
        assertEquals(3, report.rekeyedRecords)
        assertTrue(report.masterKeyDeleted)
        assertFalse("master key file must be gone", File(vaultDirectory, FileBackedKeyRing.MASTER_KEY_FILE_NAME).exists())

        // Verify every credential under the OS ring via the PUBLIC vault API (default = AndroidKeyRing).
        val osVault = PluginCredentialVault(vaultDirectory)
        assertEquals(secretA.toList(), (requireNotNull((osVault.read(pluginA, "tok") as VaultSuccess).value)).toList())
        assertEquals(secretB.toList(), (requireNotNull((osVault.read(pluginB, "tok") as VaultSuccess).value)).toList())
        assertTrue((osVault.read(pluginA, "refresh") as VaultSuccess).value != null)
    }
}
