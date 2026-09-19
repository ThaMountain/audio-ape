package com.audioape.plugin.protocol

/**
 * Typed outcome of an authorization capability action.
 *
 * The grant names a credential id the host will store in the app-private logical vault;
 * it never carries a raw secret across the boundary (capability table §2.4, §F Vault).
 */
data class CredentialGrant(
    val credentialId: String,
    val scopes: List<String>,
    val expiresAt: Long? = null,
) {
    init {
        requireNonBlank(credentialId, "credential grant credential id")
        require(scopes.isNotEmpty()) { "a credential grant needs at least one scope" }
        require(scopes.all { it.isNotBlank() }) { "credential grant scopes must be non-blank" }
        require(expiresAt == null || expiresAt > 0L) {
            "credential grant expiry must be a positive epoch millis when known"
        }
    }
}
