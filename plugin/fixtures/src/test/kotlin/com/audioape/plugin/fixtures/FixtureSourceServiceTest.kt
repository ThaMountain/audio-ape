package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.AcquisitionPlan
import com.audioape.plugin.protocol.CatalogItem
import com.audioape.plugin.protocol.PluginCapability
import com.audioape.plugin.protocol.PluginErrorCode
import com.audioape.plugin.protocol.SourceReleaseCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureSourceServiceTest {
    @Test
    fun `catalog returns exactly one confirmed public domain audiobook`() {
        val result = FixtureSourceService.catalog(PluginCapability.CATALOG)

        assertTrue(result.isSuccess)
        val items = result.value as List<CatalogItem>
        assertEquals(1, items.size)
        val item = items.single()

        assertEquals("64", item.workAliases.single())
        assertEquals("Joseph Conrad", item.author)
        assertEquals("Kristin LeMoine", item.narrator)
        assertEquals("English", item.language)
        assertTrue(item.confirmedAudioEvidence.isNotBlank())
        assertTrue(item.provenance.startsWith("https://librivox.org/api/feed/audiobooks/?id=64"))
    }

    @Test
    fun `catalog evidence uses the real archive org track urls`() {
        val result = FixtureSourceService.catalog(PluginCapability.CATALOG)
        val item = result.value as List<CatalogItem>
        val evidence = item.single().confirmedAudioEvidence

        assertTrue(
            evidence.contains(
                "https://www.archive.org/download/heart_of_darkness/heart_of_darkness_1a_conrad_64kb.mp3",
            ),
        )
        assertTrue(evidence.contains("heart_of_darkness_3b_conrad_64kb.mp3"))
    }

    @Test
    fun `sources returns exactly two distinct releases for the known edition`() {
        val result = FixtureSourceService.sources(PluginCapability.SOURCE_SEARCH, "librivox:64")

        assertTrue(result.isSuccess)
        val candidates = result.value as SourceReleaseCandidates
        assertEquals(2, candidates.matches.size)
        assertEquals(0, candidates.uncertain.size)

        val releases = candidates.matches.map { it.release }
        assertTrue(releases.all { it.editionKey == "librivox:64" })
        assertEquals(2, releases.map { it.sourceReleaseId }.distinct().size)
        assertTrue(releases.any { it.bitrateKbps == 64 && it.partCount == 6 })
        assertTrue(releases.any { it.bitrateKbps == 64 && it.format == "mp3-64kbps-zip" })
        assertTrue(releases.any { it.partCount == null })
    }

    @Test
    fun `sources returns typed empty for an unknown edition, not an error`() {
        val result = FixtureSourceService.sources(PluginCapability.SOURCE_SEARCH, "librivox:999")

        assertTrue(result.isSuccess)
        val candidates = result.value as SourceReleaseCandidates
        assertTrue(candidates.matches.isEmpty())
        assertTrue(candidates.uncertain.isEmpty())
        assertEquals("librivox:999", candidates.editionKey)
    }

    @Test
    fun `resolve round-trips a release to a deterministic acquisition plan`() {
        val sources = FixtureSourceService.sources(PluginCapability.SOURCE_SEARCH, "librivox:64")
        val releaseId =
            (sources.value as SourceReleaseCandidates)
                .matches
                .first()
                .release.sourceReleaseId

        val planResult = FixtureSourceService.resolve(PluginCapability.ACQUISITION_RESOLVER, releaseId)
        assertTrue(planResult.isSuccess)
        val plan = planResult.value as AcquisitionPlan
        assertEquals(releaseId, plan.releaseId)
        assertEquals(releaseId, plan.sourceReleaseId)
        assertEquals("librivox-fixture", plan.provenance.provider)
        assertTrue(plan.confidence.exactEdition)
        assertTrue(plan.confidence.verifiedAudio)
        assertTrue(plan.files.isNotEmpty())
        assertTrue(plan.files.all { it.name.isNotBlank() && it.sizeBytes >= 0L })

        // Deterministic: two calls return equal plans.
        val again = FixtureSourceService.resolve(PluginCapability.ACQUISITION_RESOLVER, releaseId)
        assertEquals(plan, again.value)
    }

    @Test
    fun `resolve of an unknown release id is a typed not-found error`() {
        val result = FixtureSourceService.resolve(PluginCapability.ACQUISITION_RESOLVER, "librivox:64:missing")

        assertFalse(result.isSuccess)
        assertNull(result.value)
        assertEquals(PluginErrorCode.NOT_FOUND, result.rejection?.code)
    }

    @Test
    fun `fixture operations require the declared pairing capability`() {
        val catalogMismatch = FixtureSourceService.catalog(PluginCapability.SOURCE_SEARCH)
        assertFalse(catalogMismatch.isSuccess)
        assertEquals(PluginErrorCode.CAPABILITY_MISMATCH, catalogMismatch.rejection?.code)
        assertTrue(catalogMismatch.rejection?.detail?.contains("fixture.catalog.v0") == true)

        val sourcesMismatch = FixtureSourceService.sources(PluginCapability.ACQUISITION_RESOLVER, "librivox:64")
        assertFalse(sourcesMismatch.isSuccess)
        assertEquals(PluginErrorCode.CAPABILITY_MISMATCH, sourcesMismatch.rejection?.code)

        val resolveMismatch = FixtureSourceService.resolve(PluginCapability.CATALOG, "librivox:64:parts:64kbps")
        assertFalse(resolveMismatch.isSuccess)
        assertEquals(PluginErrorCode.CAPABILITY_MISMATCH, resolveMismatch.rejection?.code)
    }
}
