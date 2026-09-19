package com.audioape.plugin.protocol

/**
 * Closed set of transfer job life-cycle states, mirroring §G `queryStatus`
 * (`READY / PENDING / ERROR`). There is deliberately no "resolving" value here: the
 * step that produced the job lives in [ResolveSessionStep], and the download state
 * machine (`QUEUED → … → COMPLETED`) remains host-owned.
 */
enum class ProviderJobStatus {
    /** The provider finished the job and the file list is available. */
    READY,

    /** The provider is still working; the host should poll per its bounded policy. */
    PENDING,

    /** The provider reported the job failed; the typed error is carried separately. */
    ERROR,
}

/**
 * Closed progression of one resolve attempt across the §G surface
 * (`prepareSource → queryStatus → selectFiles → resolveLink`). The host advances the
 * step as each typed action returns; the simulator implements the same ordering.
 */
enum class ResolveSessionStep {
    PREPARE_SOURCE,
    QUERY_STATUS,
    SELECT_FILES,
    RESOLVE_LINK,
}

/**
 * One offer in a provider's READY file list, bounded by the same file/size budgets as
 * §G `selectFiles`. `sizeBytes` is the whole-file byte size the provider states.
 */
data class ProviderFile(
    val fileId: String,
    val name: String,
    val sizeBytes: Long,
) {
    init {
        requireNonBlank(fileId, "provider file id")
        requireNonBlank(name, "provider file name")
        require(sizeBytes >= 0L) { "provider file size must be nonnegative" }
    }
}

/**
 * Immutable lease on a provider job. The job lives only in this descriptor; it never
 * carries a credential, token, or raw URL. [expiresAt] is an epoch-millis deadline
 * after which the job must not be reused (matching §G PENDING/expiry handling).
 */
data class ProviderJob(
    val providerJobId: String,
    val status: ProviderJobStatus,
    val files: List<ProviderFile> = emptyList(),
    val expiresAt: Long? = null,
    val detail: String? = null,
) {
    init {
        requireNonBlank(providerJobId, "provider job id")
        require(expiresAt == null || expiresAt > 0L) {
            "provider job expiry must be a positive epoch millis when known"
        }
        detail?.let { requireNonBlank(it, "provider job detail") }
        require(providerFileIdsAreUnique(files)) { "provider file ids must be unique" }
    }
}

/**
 * `selectFiles` outcome: the approved file manifest plus the single file this plan
 * resolves. `matches` must be non-empty (absent files are a typed empty result, never
 * a fabricated manifest) and may not be a silent substitution: the caller reconciles
 * it against the source release evidence before ever resolving a link.
 */
data class ResolveSession(
    val providerJobId: String,
    val step: ResolveSessionStep,
    val matches: List<ProviderFile> = emptyList(),
    val resolvedFile: ResolvedDownload? = null,
) {
    init {
        requireNonBlank(providerJobId, "resolve session provider job id")
        require(matches.isNotEmpty()) { "a resolve session needs a non-empty file manifest" }
        require(providerFileIdsAreUnique(matches)) { "provider file ids must be unique" }
    }
}

private fun providerFileIdsAreUnique(files: List<ProviderFile>): Boolean = files.map { it.fileId }.distinct().size == files.size
