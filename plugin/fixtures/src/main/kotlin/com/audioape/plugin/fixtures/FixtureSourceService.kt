package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.AcquisitionPlan
import com.audioape.plugin.protocol.CatalogItem
import com.audioape.plugin.protocol.PluginCapability
import com.audioape.plugin.protocol.PluginErrorCode
import com.audioape.plugin.protocol.PluginResult
import com.audioape.plugin.protocol.SourceReleaseCandidates
import com.audioape.plugin.protocol.pluginRejected
import com.audioape.plugin.protocol.pluginSuccess

/**
 * Deterministic `fixture.*.v0` host primitives for the bundled fixture source.
 *
 * Every call is gated by [FixtureCapabilityGate]: the operation may only run when the
 * caller holds the pairing capability from capability table §3. This is intentionally a
 * thin, testable seam — the real host interpreter (AA-023) will enforce the same
 * pairing at its own boundary. All three primitives are static-data only, never
 * network, and never import Room/SAF/player.
 */
object FixtureSourceService {
    /** `fixture.catalog.v0`: exactly one confirmed public-domain audiobook. */
    fun catalog(held: PluginCapability): PluginResult<List<CatalogItem>> {
        FixtureCapabilityGate
            .requirePairing<List<CatalogItem>>(held, "fixture.catalog.v0")
            ?.let { return it }
        return pluginSuccess(FixtureLibrary.catalogItems)
    }

    /** `fixture.sources.v0`: release index for one edition, typed empty for unknown. */
    fun sources(
        held: PluginCapability,
        editionKey: String,
    ): PluginResult<SourceReleaseCandidates> {
        FixtureCapabilityGate
            .requirePairing<SourceReleaseCandidates>(held, "fixture.sources.v0")
            ?.let { return it }
        return pluginSuccess(FixtureLibrary.sourcesFor(editionKey))
    }

    /** `fixture.resolve.v0`: deterministic acquisition plan for one release id. */
    fun resolve(
        held: PluginCapability,
        releaseId: String,
    ): PluginResult<AcquisitionPlan> {
        FixtureCapabilityGate
            .requirePairing<AcquisitionPlan>(held, "fixture.resolve.v0")
            ?.let { return it }
        val plan = FixtureLibrary.resolveRelease(releaseId)
        if (plan == null) {
            return pluginRejected(
                PluginErrorCode.NOT_FOUND,
                "no fixture acquisition plan for release id '$releaseId'",
            )
        }
        return pluginSuccess(plan)
    }
}
