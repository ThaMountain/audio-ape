package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.AcquisitionPlan
import com.audioape.plugin.protocol.CatalogItem
import com.audioape.plugin.protocol.DeferredDownload
import com.audioape.plugin.protocol.DownloadDescriptor
import com.audioape.plugin.protocol.PluginCapability
import com.audioape.plugin.protocol.PluginErrorCode
import com.audioape.plugin.protocol.PluginResult
import com.audioape.plugin.protocol.ProviderFile
import com.audioape.plugin.protocol.ProviderJob
import com.audioape.plugin.protocol.ProviderJobStatus
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

    /**
     * `resolver.debrid.job.v0`: creates a provider job for the release (READY, fixture
     * file list). The job is an immutable descriptor — it never holds a token.
     */
    fun prepareSource(
        held: PluginCapability,
        releaseId: String,
    ): PluginResult<ProviderJob> {
        FixtureCapabilityGate
            .requirePairing<ProviderJob>(held, "fixture.resolve.v0")
            ?.let { return it }
        val plan = FixtureLibrary.resolveRelease(releaseId)
        if (plan == null) {
            return pluginRejected(
                PluginErrorCode.NOT_FOUND,
                "no fixture acquisition plan for release id '$releaseId'",
            )
        }
        val jobId = FixtureResolverSession.PROVIDER_JOB_PREFIX + ":" + releaseId
        return pluginSuccess(
            ProviderJob(
                providerJobId = jobId,
                status = ProviderJobStatus.READY,
                files = plannedProviderFiles(plan),
            ),
        )
    }

    /**
     * `resolver.debrid.files.v0`: selects the file a release resolves to. Expected-file
     * mismatches (wrong edition) and empty lists fail by contract; see §G.
     */
    fun selectFiles(
        held: PluginCapability,
        releaseId: String,
        expectedFileIds: List<String>,
    ): PluginResult<DeferredDownload> {
        FixtureCapabilityGate
            .requirePairing<DeferredDownload>(held, "fixture.resolve.v0")
            ?.let { return it }
        requireExpectedFiles(expectedFileIds)?.let { return it }
        val plan = FixtureLibrary.resolveRelease(releaseId)
        if (plan == null) {
            return pluginRejected(
                PluginErrorCode.NOT_FOUND,
                "no fixture acquisition plan for release id '$releaseId'",
            )
        }
        val availableIds = plannedProviderFiles(plan).map { it.fileId }
        if (expectedFileIds.none { it in availableIds }) {
            return pluginRejected(
                PluginErrorCode.NOT_FOUND,
                "selected file ids do not match the provider file list",
            )
        }
        return pluginSuccess(
            DeferredDownload(
                releaseId = releaseId,
                status = ProviderJobStatus.READY,
            ),
        )
    }

    /** §G `queryStatus`: the fixture job is READY until its own expiry. */
    fun queryStatus(
        held: PluginCapability,
        providerJob: ProviderJob,
    ): PluginResult<ProviderJobStatus> {
        FixtureCapabilityGate
            .requirePairing<ProviderJobStatus>(held, "fixture.resolve.v0")
            ?.let { return it }
        FixtureResolverSession
            .requireJobActive(providerJob.providerJobId, providerJob.expiresAt, nowMillis())
            ?.let { return it }
        return pluginSuccess(providerJob.status)
    }

    /** §G `resolveLink`: the expiring HTTPS URL for the selected file. */
    fun resolveLink(
        held: PluginCapability,
        releaseId: String,
    ): PluginResult<DownloadDescriptor> {
        FixtureCapabilityGate
            .requirePairing<DownloadDescriptor>(held, "fixture.resolve.v0")
            ?.let { return it }
        val plan = FixtureLibrary.resolveRelease(releaseId)
        if (plan == null) {
            return pluginRejected(
                PluginErrorCode.NOT_FOUND,
                "no fixture acquisition plan for release id '$releaseId'",
            )
        }
        val now = nowMillis()
        if (now >= (plan.download?.expiresAt ?: Long.MAX_VALUE)) {
            return pluginRejected(
                PluginErrorCode.LIMIT_EXCEEDED,
                "fixture download link expired; re-resolve",
            )
        }
        val descriptor = plan.download
        if (descriptor == null) {
            return pluginRejected(
                PluginErrorCode.SOURCE_UNAVAILABLE,
                "fixture release has no resolved download descriptor",
            )
        }
        return pluginSuccess(descriptor)
    }
}

/**
 * The fixture job's provider file list, derived from the same plan the static resolve
 * emits. This is the §G evidence that a later file selection must reconcile against
 * (wrong edition = no intersection, absent files = empty plan, both typed).
 */
private fun plannedProviderFiles(plan: AcquisitionPlan): List<ProviderFile> =
    plan.files.mapIndexed { index, file ->
        ProviderFile(
            fileId = FixtureResolverSession.PROVIDER_JOB_PREFIX + ":file:" + index,
            name = file.name,
            sizeBytes = file.sizeBytes,
        )
    }

/**
 * Injectable epoch-millis clock for the static provider statements, mirroring the
 * simulator's injected clock and the playback checkpoint recorder's `now` seam. Pure
 * JVM, no timers, no network.
 */
fun nowMillis(): Long = System.currentTimeMillis()
