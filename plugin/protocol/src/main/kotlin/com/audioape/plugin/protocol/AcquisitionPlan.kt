package com.audioape.plugin.protocol

/**
 * One release file a resolver will produce, bounded and host-validated.
 *
 * `url` is optional because a fixture or provider may emit a file descriptor whose
 * expiring link only becomes final later; the host never treats a null URL as media it
 * can already fetch. `name` is a display/basename, never an arbitrary filesystem path.
 */
data class ReleaseFile(
    val name: String,
    val sizeBytes: Long,
    val url: String?,
) {
    init {
        requireNonBlank(name, "release file name")
        require(sizeBytes >= 0L) { "release file size must be nonnegative" }
        requireOptionalUrl(url, "release file URL")
    }
}

/** Provenance facts a resolver is willing to stand behind. */
data class Provenance(
    val sourceId: String,
    val sourceReleaseId: String,
    val provider: String,
) {
    init {
        requireNonBlank(sourceId, "provenance source id")
        requireNonBlank(sourceReleaseId, "provenance source release id")
        requireNormalizedText(provider, "provenance provider")
    }
}

/** Match-confidence and release-integrity flags set by the resolver, never by the plugin. */
data class MatchConfidence(
    val exactEdition: Boolean = false,
    val verifiedAudio: Boolean = false,
) {
    init {
        require(!verifiedAudio || exactEdition) {
            "verified audio implies exact edition confidence"
        }
    }
}

/**
 * Immutable download descriptor a resolver returns for one chosen release.
 *
 * Mirrors capability table §2.5 `AcquisitionPlan` and the bounded
 * `ResolvedDownload` descriptor shape from §A/§G. It is data a host transfers with its
 * own state machine; it never starts a download by itself.
 */
data class AcquisitionPlan(
    val releaseId: String,
    val files: List<ReleaseFile>,
    val provenance: Provenance,
    val confidence: MatchConfidence = MatchConfidence(),
    val sourceReleaseId: String,
) {
    init {
        requireNonBlank(releaseId, "acquisition plan release id")
        require(files.isNotEmpty()) { "an acquisition plan needs at least one release file" }
        requireNonBlank(sourceReleaseId, "acquisition plan source release id")
    }
}
