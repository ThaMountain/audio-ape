package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.AcquisitionPlan
import com.audioape.plugin.protocol.CatalogItem
import com.audioape.plugin.protocol.MatchConfidence
import com.audioape.plugin.protocol.Provenance
import com.audioape.plugin.protocol.ReleaseFile
import com.audioape.plugin.protocol.SourceMatchUncertainty
import com.audioape.plugin.protocol.SourceReleaseCandidate
import com.audioape.plugin.protocol.SourceReleaseCandidates
import com.audioape.plugin.protocol.SourceReleaseMatch
import com.audioape.plugin.protocol.SourceReleaseUncertain

/**
 * The fixture source's single-edition release index (bundled, no network).
 *
 * This is the `fixture.sources.v0` backing store. It is deliberately tiny; the point is
 * to prove the typed boundary shapes, not to simulate a real catalog. Unknown optional
 * fields on the two releases stay null. Both releases are distinct in format/bitrate or
 * part count.
 */
object FixtureLibrary {
    const val SOURCE_ID = "librivox"
    const val RELEASE_64KBPS = "librivox:64:parts:64kbps"
    const val RELEASE_64KBPS_ZIP = "librivox:64:zip:64kbps"
    const val EDITION_KEY = "librivox:64"

    val catalogItems: List<CatalogItem> = listOf(HeartOfDarknessFixture.catalogItem())

    fun sourcesFor(editionKey: String): SourceReleaseCandidates {
        if (editionKey != EDITION_KEY) {
            return SourceReleaseCandidates(
                editionKey = editionKey,
                matches = emptyList(),
                uncertain = emptyList(),
            )
        }
        val base = HeartOfDarknessFixture
        return SourceReleaseCandidates(
            editionKey = editionKey,
            matches =
                listOf(
                    SourceReleaseMatch(
                        release =
                            SourceReleaseCandidate(
                                sourceReleaseId = RELEASE_64KBPS,
                                editionKey = editionKey,
                                format = "mp3-64kbps-parts",
                                bitrateKbps = 64,
                                partCount = 6,
                                sizeBytes = 8_260_000L,
                                durationMilliseconds = base.DURATION_MS,
                                container = null,
                                codec = null,
                                title = null,
                            ),
                    ),
                    SourceReleaseMatch(
                        release =
                            SourceReleaseCandidate(
                                sourceReleaseId = RELEASE_64KBPS_ZIP,
                                editionKey = editionKey,
                                format = "mp3-64kbps-zip",
                                bitrateKbps = 64,
                                partCount = null,
                                sizeBytes = 8_260_000L,
                                durationMilliseconds = base.DURATION_MS,
                                container = null,
                                codec = null,
                                title = null,
                            ),
                    ),
                ),
            uncertain = emptyList(),
        )
    }

    fun resolveRelease(releaseId: String): AcquisitionPlan? {
        val base = HeartOfDarknessFixture
        val files =
            if (releaseId == RELEASE_64KBPS) {
                listOf(
                    ReleaseFile(
                        name = "heart_of_darkness_1a_conrad_64kb.mp3",
                        sizeBytes = 1_370_000L,
                        url = base.FIRST_TRACK_URL,
                    ),
                )
            } else if (releaseId == RELEASE_64KBPS_ZIP) {
                listOf(
                    ReleaseFile(
                        name = "heart_of_darkness.zip",
                        sizeBytes = 8_260_000L,
                        url = base.ZIP_URL,
                    ),
                )
            } else {
                return null
            }
        return AcquisitionPlan(
            releaseId = releaseId,
            sourceReleaseId = releaseId,
            files = files,
            provenance =
                Provenance(
                    sourceId = SOURCE_ID,
                    sourceReleaseId = releaseId,
                    provider = "librivox-fixture",
                ),
            confidence = MatchConfidence(exactEdition = true, verifiedAudio = true),
        )
    }
}
