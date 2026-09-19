package com.audioape.plugin.host

import kotlin.ByteArray

/**
 * Typed outcome of one [PluginCredentialVault] operation.
 *
 * Mirrors the host-boundary rule ("no exceptions cross the boundary", AA-018) for the
 * vault: every operation returns a sealed [VaultResult] with exactly one case, and the
 * caller never has to inspect raw Android exception types. `detail` strings are
 * parameter-free and never carry plugin ids, credential ids, or secrets (rule 11).
 */
sealed interface VaultResult

/** The vault operation completed and produced [value]. */
data class VaultSuccess(
    val value: kotlin.ByteArray?,
) : VaultResult {
    init {
        require(value == null || value.size in 1..VaultLimits.MAX_SECRET_BYTES) {
            "a stored secret must be non-empty and within the vault size limit"
        }
    }
}

/** The requested (pluginId, credentialId) pair has no ciphertext entry. */
data object VaultNotFound : VaultResult

/**
 * The Keystore permanently invalidated the plugin's key (e.g. a strongbox or biometric
 * rule changed, or the key was revoked). The host MUST NOT re-create or re-decrypt
 * anything: the caller must re-run the plugin's authorization flow and re-consent, and
 * only then call [PluginCredentialVault.clearAll] to drop unrecoverable state.
 */
data object VaultKeyInvalidated : VaultResult

/**
 * Ciphertext could not be read, written, or integrity-verified (missing/corrupt file,
 * tamper, IO). This is never treated as "not found" and never silently healed; any
 * plaintext bytes are deliberately destroyed on decrypt failure (valueless corruption).
 */
data object VaultIoFailure : VaultResult

/**
 * A plugin asked for access outside its namespaced key scope. Cross-plugin reads are
 * impossible by construction (aliases and files are digest-prefixed and
 * canonicalized), so this normally only fires on a naming or indexing bug — and must
 * be loud.
 */
data object VaultForbidden : VaultResult
