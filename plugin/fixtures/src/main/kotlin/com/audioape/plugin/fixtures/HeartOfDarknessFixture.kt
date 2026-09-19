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
 * Bundled, checksum-pinned fixture data for the single confirmed public-domain
 * audiobook (Heart of Darkness, LibriVox id 64).
 *
 * The real data source is `contracts/catalog-librivox-fixtures.json` (see
 * `docs/decisions/0002-catalog-provider-librivox.md` for the legal permission and
 * attribution register). This file is a build-time copy so the fixture module is pure
 * JVM with zero network access; the test suite re-checks the pinned SHA-256 against
 * the repository file to prove the copy is still current.
 *
 * All optional fields a source would not state are left null here deliberately —
 * automated tests assert "unknown stays null".
 */
object HeartOfDarknessFixture {
    const val WORK_ID = "64"
    const val EDITION_ID = "64"
    const val TITLE = "Heart of Darkness"
    const val AUTHOR = "Joseph Conrad"
    const val NARRATOR = "Kristin LeMoine"
    const val LANGUAGE = "English"
    const val DURATION_MS = 15_012_000L
    const val DESCRIPTION =
        "Set in a time of oppressive colonisation, when large areas of the world were still " +
            "unknown to Europe, and Africa was literally on maps and minds as a mysterious " +
            "shadow, Heart of Darkness famously explores the rituals of civilisation and " +
            "barbarism, and the frighteningly fine line between them. (Summary written by " +
            "Marlo Dianne)"
    const val COVER_URL = "https://archive.org/download/heart_of_darkness/Heart_of_Darkness.jpg"

    const val ARCHIVE_BASE = "https://www.archive.org/download/heart_of_darkness"
    const val FIRST_TRACK_URL = "$ARCHIVE_BASE/heart_of_darkness_1a_conrad_64kb.mp3"
    const val ZIP_URL = "https://archive.org/compress/heart_of_darkness/formats=64KBPS MP3&file=/heart_of_darkness.zip"
    const val PROVENANCE =
        "https://librivox.org/api/feed/audiobooks/?id=64&format=json&extended=1&coverart=1"

    /** SHA-256 of `contracts/catalog-librivox-fixtures.json` 2026-09-19. */
    const val FIXTURE_SHA256 =
        "72fe32fe51e79e76e58740c3e7853ccb71dcd0d7289fb3cbdd4bbc23bcc928e9"

    val confirmedAudioEvidence: String =
        evidence(
            sampleTracks =
                listOf(
                    "$ARCHIVE_BASE/heart_of_darkness_1a_conrad_64kb.mp3",
                    "$ARCHIVE_BASE/heart_of_darkness_1b_conrad_64kb.mp3",
                    "$ARCHIVE_BASE/heart_of_darkness_2a_conrad_64kb.mp3",
                    "$ARCHIVE_BASE/heart_of_darkness_2b_conrad_64kb.mp3",
                    "$ARCHIVE_BASE/heart_of_darkness_3a_conrad_64kb.mp3",
                    "$ARCHIVE_BASE/heart_of_darkness_3b_conrad_64kb.mp3",
                ),
        )

    fun catalogItem(): CatalogItem =
        CatalogItem(
            workAliases = listOf(WORK_ID),
            editionAliases = listOf(EDITION_ID),
            author = AUTHOR,
            narrator = NARRATOR,
            language = LANGUAGE,
            audioPublicationDate = null,
            description = DESCRIPTION,
            coverUrl = COVER_URL,
            provenance = PROVENANCE,
            confirmedAudioEvidence = confirmedAudioEvidence,
        )
}

private fun evidence(sampleTracks: List<String>): String =
    sampleTracks.joinToString("\n", prefix = "LibriVox id 64 confirmed audio evidence:\n")
