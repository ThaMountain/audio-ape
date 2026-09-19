package com.audioape.plugin.host

import java.io.File
import kotlin.ByteArray

/**
 * One canonicalized (pluginId, [credentialId]) scope. Alias and file names are derived
 * once here so the vault, the keystore seam and the file codec agree on one digest
 * namespace: `vault.<sha256(pluginId)>` for the keystore alias and
 * `vault.<sha256(pluginId)>.bin` for the on-disk file. A plugin id can never name an
 * alias that resolves to another plugin's scope, and the same derivation is used for
 * the keystore and the filesystem, so cross-plugin reads are impossible by
 * construction.
 */
data class VaultKeyScoped(
    val pluginId: String,
    val credentialId: String?,
    val vaultDirectory: File,
) {
    val pluginDigest: String
        get() = VaultKeyRing.pluginDigest(pluginId)

    /** Keystore alias for this plugin's key entry. */
    val alias: String
        get() = "vault.$pluginDigest"

    /** App-private ciphertext file for this plugin, under the caller's vault dir. */
    val pluginFile: File
        get() = File(vaultDirectory, "vault.$pluginDigest.bin")
}
