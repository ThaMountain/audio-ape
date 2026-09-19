package com.audioape.plugin.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolShapesTest {
    @Test
    fun `catalog item requires non-empty confirmed audio evidence`() {
        val item = catalogItem()
        assertTrue(item.confirmedAudioEvidence.isNotBlank())

        assertThrows(IllegalArgumentException::class.java) {
            item.copy(confirmedAudioEvidence = " ")
        }
    }

    @Test
    fun `catalog item keeps unknown optional fields null`() {
        val item =
            CatalogItem(
                workAliases = listOf("work-1"),
                editionAliases = listOf("edition-1"),
                author = "Example Author",
                narrator = null,
                language = null,
                audioPublicationDate = null,
                description = null,
                coverUrl = null,
                provenance = "https://example.org/catalog/1",
                confirmedAudioEvidence = "https://example.org/audio/track-1.mp3",
            )

        assertNull(item.narrator)
        assertNull(item.language)
        assertNull(item.audioPublicationDate)
        assertNull(item.description)
        assertNull(item.coverUrl)
    }

    @Test
    fun `catalog and edition metadata are immutable value objects`() {
        val item = catalogItem()
        val metadata = editionMetadata()
        assertEquals(item, item.copy())
        assertEquals(metadata, metadata.copy())

        assertThrows(IllegalArgumentException::class.java) {
            item.copy(workAliases = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            metadata.copy(typedFields = emptyList())
        }
    }

    @Test
    fun `edition metadata must include the requested field type`() {
        val metadata = editionMetadata()
        assertEquals(EditionMetadataFieldType.TITLE, metadata.requestedBy)
        assertTrue(metadata.typedFields.any { it.field == metadata.requestedBy })

        assertThrows(IllegalArgumentException::class.java) {
            metadata.copy(
                requestedBy = EditionMetadataFieldType.AUTHOR,
                typedFields =
                    listOf(EditionMetadataField(EditionMetadataFieldType.TITLE, "A Title")),
            )
        }
    }

    @Test
    fun `source release candidates distinguish matches from uncertain releases`() {
        val candidates = sourceCandidates()
        assertEquals(1, candidates.matches.size)
        assertEquals(1, candidates.uncertain.size)
        assertEquals(
            SourceMatchUncertainty.NONE,
            candidates.matches.single().uncertainty,
        )
        assertEquals(
            SourceMatchUncertainty.UNSTATED_FIELD,
            candidates.uncertain.single().uncertainty,
        )
        assertTrue(candidates.matches.none { it.uncertainty != SourceMatchUncertainty.NONE })
        assertTrue(candidates.uncertain.all { it.uncertainty != SourceMatchUncertainty.NONE })
    }

    @Test
    fun `uncertain release cannot claim no uncertainty`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceReleaseUncertain(
                release = sourceRelease(),
                uncertainty = SourceMatchUncertainty.NONE,
            )
        }
    }

    @Test
    fun `acquisition plan requires files and proven confidence semantics`() {
        val plan = acquisitionPlan()
        assertTrue(plan.files.isNotEmpty())
        assertTrue(plan.confidence.exactEdition)
        assertTrue(plan.confidence.verifiedAudio)

        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(files = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatchConfidence(exactEdition = false, verifiedAudio = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(releaseId = " ")
        }
    }

    @Test
    fun `release file url is optional and unknown stays null`() {
        val file = ReleaseFile(name = "track.mp3", sizeBytes = 123L, url = null)
        assertNull(file.url)
        assertEquals(123L, file.sizeBytes)

        assertThrows(IllegalArgumentException::class.java) {
            ReleaseFile(name = "track.mp3", sizeBytes = -1L, url = null)
        }
    }

    @Test
    fun `provenance and match confidence are immutable value objects`() {
        val provenance = provenance()
        val confidence = MatchConfidence(exactEdition = true, verifiedAudio = true)
        assertEquals(provenance, provenance.copy())
        assertEquals(confidence, confidence.copy())
        assertNotEquals(
            confidence,
            MatchConfidence(exactEdition = true, verifiedAudio = false),
        )
    }

    @Test
    fun `credential grant carries no raw secret and validates scopes`() {
        val grant = CredentialGrant("fixture-cred", listOf("read"), expiresAt = null)
        assertNull(grant.expiresAt)
        assertEquals("fixture-cred", grant.credentialId)
        assertThrows(IllegalArgumentException::class.java) {
            CredentialGrant(credentialId = "cred", scopes = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialGrant(credentialId = "cred", scopes = listOf(" "), expiresAt = 5L)
        }
    }

    private fun catalogItem() =
        CatalogItem(
            workAliases = listOf("work-64"),
            editionAliases = listOf("edition-64"),
            author = "Joseph Conrad",
            narrator = "Kristin LeMoine",
            language = "English",
            audioPublicationDate = null,
            description = null,
            coverUrl = "https://archive.org/download/heart_of_darkness/Heart_of_Darkness.jpg",
            provenance = "https://librivox.org/api/feed/audiobooks/?id=64",
            confirmedAudioEvidence = "https://www.archive.org/download/heart_of_darkness/heart_of_darkness_1a_conrad_64kb.mp3",
        )

    private fun editionMetadata() =
        EditionMetadata(
            backendEditionId = "64",
            typedFields = listOf(EditionMetadataField(EditionMetadataFieldType.TITLE, "Heart of Darkness")),
            requestedBy = EditionMetadataFieldType.TITLE,
        )

    private fun sourceRelease() =
        SourceReleaseCandidate(
            sourceReleaseId = "librivox:64:parts:64kbps",
            editionKey = "librivox:64",
            format = "mp3-64kbps-parts",
            bitrateKbps = 64,
            partCount = 6,
            sizeBytes = 8_260_000L,
            durationMilliseconds = 15_012_000L,
            container = null,
            codec = null,
            title = null,
        )

    private fun sourceCandidates() =
        SourceReleaseCandidates(
            editionKey = "librivox:64",
            matches =
                listOf(
                    SourceReleaseMatch(
                        release = sourceRelease(),
                        uncertainty = SourceMatchUncertainty.NONE,
                    ),
                ),
            uncertain =
                listOf(
                    SourceReleaseUncertain(
                        release =
                            sourceRelease().copy(
                                sourceReleaseId = "librivox:64:unknown",
                                partCount = null,
                            ),
                        uncertainty = SourceMatchUncertainty.UNSTATED_FIELD,
                    ),
                ),
        )

    private fun provenance() =
        Provenance(
            sourceId = "librivox",
            sourceReleaseId = "librivox:64:parts:64kbps",
            provider = "librivox-fixture",
        )

    private fun acquisitionPlan() =
        AcquisitionPlan(
            releaseId = "librivox:64:parts:64kbps",
            sourceReleaseId = "librivox:64:parts:64kbps",
            files =
                listOf(
                    ReleaseFile(
                        name = "heart_of_darkness_1a_conrad_64kb.mp3",
                        sizeBytes = 1_370_000L,
                        url = "https://www.archive.org/download/heart_of_darkness/heart_of_darkness_1a_conrad_64kb.mp3",
                    ),
                ),
            provenance =
                Provenance(
                    sourceId = "librivox",
                    sourceReleaseId = "librivox:64:parts:64kbps",
                    provider = "librivox-fixture",
                ),
            confidence = MatchConfidence(exactEdition = true, verifiedAudio = true),
        )
}
