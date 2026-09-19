package com.audioape.plugin.protocol

/**
 * `ResolvedDownload` per §G `resolveLink`: a short-lived HTTPS URL plus the identity
 * facts a host verifies while the transfer runs.
 *
 * Every download link is **expiring**: [expiresAt] is a positive epoch-millis deadline
 * at which the host must treat the link as unusable and never retry it in a tight loop.
 * [sha256] when present is the deterministic whole-file SHA-256 for this edition part.
 * The URL is the same host-validated, no-query/no-fragment shape the rest of the
 * boundary enforces. This object is data only: it never opens a transfer.
 */
data class DownloadDescriptor(
    val url: String,
    val expiresAt: Long,
    val sha256: String? = null,
) {
    init {
        requireOptionalUrl(url, "download descriptor URL")
        require(expiresAt > 0L) { "download descriptor expiry must be a positive epoch millis" }
        sha256?.let { candidate ->
            require(isSha256Hex(candidate)) {
                "download descriptor sha256 must be 64 lowercase hex characters"
            }
        }
    }

    fun isExpiredAt(now: Long): Boolean {
        require(now > 0L) { "now must be a positive epoch millis" }
        return now >= expiresAt
    }
}

/**
 * §G `resolveLink` completion: a [DownloadDescriptor] the host may now transfer. This
 * is the value half of the simulator's `resolve` outcome — the deterministic, typed
 * twin of the impl's fixture descriptor.
 */
data class ResolvedDownload(
    val descriptor: DownloadDescriptor,
    val fileId: String,
) {
    init {
        requireNonBlank(fileId, "resolved download file id")
    }
}

private fun isSha256Hex(candidate: String): Boolean = candidate.length == 64 && candidate.all { characterIsHex(it) }

private fun characterIsHex(character: Char): Boolean =
    (character >= '0' && character <= '9') ||
        (character >= 'a' && character <= 'f')
