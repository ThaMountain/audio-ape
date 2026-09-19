package com.audioape.plugin.protocol

/**
 * One release a source index offers for a confirmed edition, normalized to the host.
 *
 * Mirrors capability table §2.3 / §G. Unknown optional fields stay `null` rather than
 * being guessed. `sizeBytes` is the whole-recorded byte size when the source states it,
 * and is `null` when not.
 */
data class SourceReleaseCandidate(
    val sourceReleaseId: String,
    val editionKey: String,
    val format: String,
    val bitrateKbps: Int?,
    val partCount: Int?,
    val sizeBytes: Long?,
    val durationMilliseconds: Long?,
    val container: String?,
    val codec: String?,
    val title: String?,
) {
    init {
        requireNonBlank(sourceReleaseId, "source release id")
        requireNonBlank(editionKey, "edition key")
        requireNormalizedText(format, "source release format")
        require(bitrateKbps == null || bitrateKbps > 0) { "bitrate must be positive when known" }
        require(partCount == null || partCount > 0) { "part count must be positive when known" }
        require(sizeBytes == null || sizeBytes >= 0L) { "size bytes must be nonnegative when known" }
        require(durationMilliseconds == null || durationMilliseconds > 0L) {
            "duration must be positive when known"
        }
        requireOptionalNormalizedText(container, "source release container")
        requireOptionalNormalizedText(codec, "source release codec")
        requireOptionalNormalizedText(title, "source release title")
    }
}

/**
 * Explicit label for which release fields the source did not return, so the host can
 * surface honest uncertainty instead of treating nulls as validated data.
 */
enum class SourceMatchUncertainty {
    /** The source returned the release with no stated fields missing. */
    NONE,

    /** A release-level field (format/bitrate/part count) is unstated. */
    UNSTATED_FIELD,

    /** The exact edition identity could only be matched heuristically. */
    HEURISTIC_MATCH,

    /** The source advertised a release but no file list was returned. */
    NO_FILE_LIST,
}

/**
 * A matched release that is fully usable, plus the explicit reason it is not uncertain.
 * Mirrors capability table §2.3 `SourceReleaseCandidates.matches[]`.
 */
data class SourceReleaseMatch(
    val release: SourceReleaseCandidate,
    val uncertainty: SourceMatchUncertainty = SourceMatchUncertainty.NONE,
)

/**
 * A release the source returned but which carries explicit uncertainty, kept separate
 * from confident matches so the host never offers it as validated. Mirrors capability
 * table §2.3 `SourceReleaseCandidates.uncertain[]`.
 */
data class SourceReleaseUncertain(
    val release: SourceReleaseCandidate,
    val uncertainty: SourceMatchUncertainty,
) {
    init {
        require(uncertainty != SourceMatchUncertainty.NONE) {
            "an uncertain release must carry explicit uncertainty"
        }
    }
}

/**
 * Bounded result of matching one confirmed edition against one source release index.
 * `matches` and `uncertain` are both ordered lists; neither may be inferred from the other.
 */
data class SourceReleaseCandidates(
    val editionKey: String,
    val matches: List<SourceReleaseMatch>,
    val uncertain: List<SourceReleaseUncertain>,
)
