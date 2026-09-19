package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.DownloadDescriptor
import com.audioape.plugin.protocol.PluginCapability
import com.audioape.plugin.protocol.PluginErrorCode
import com.audioape.plugin.protocol.PluginResult
import com.audioape.plugin.protocol.ProviderFile
import com.audioape.plugin.protocol.ProviderJob
import com.audioape.plugin.protocol.ProviderJobStatus
import com.audioape.plugin.protocol.ResolveSession
import com.audioape.plugin.protocol.RetryPolicy
import com.audioape.plugin.protocol.pluginAuthRequired
import com.audioape.plugin.protocol.pluginRateLimited
import com.audioape.plugin.protocol.pluginRejected
import com.audioape.plugin.protocol.pluginSuccess

/**
 * Pure, deterministic test double for the §G debrid lifecycle
 * (`prepareSource → queryStatus → selectFiles → resolveLink`).
 *
 * The simulator never touches the network and never reads the wall clock: time comes
 * from the injected [FixtureResolverClock] (same seam as the playback checkpoint
 * recorder). It models the failing states a real provider adapter must handle: expired
 * links, 401 (auth-required, no tight-loop retry), 429 (rate limit with a bounded
 * retry-after), wrong-edition file lists (typed mismatch, no silent substitution), and
 * absent files (typed empty). Every surface returns a [PluginResult] — no exceptions
 * cross the boundary.
 */
class FixtureResolverSimulator(
    private val session: FixtureSimulatorSession = FixtureSimulatorSession(),
    private val clock: FixtureResolverClock = systemEpochMillisClock,
) {
    /** §G `prepareSource`: maps a release to a provider job (READY) or a typed failure. */
    fun prepareSource(
        held: PluginCapability,
        sourceReleaseId: String,
    ): PluginResult<ProviderJob> {
        FixtureCapabilityGate
            .requirePairing<ProviderJob>(held, "fixture.resolve.v0")
            ?.let { return it }
        val job = session.job
        if (job.isRejected) {
            return pluginRejected(job.rejection?.code!!, job.rejection?.detail)
        }
        val ok = job.value as SimulatorJob
        return pluginSuccess(
            ProviderJob(
                providerJobId = FixtureSimulatorSession.DEFAULT_PROVIDER_JOB_ID,
                status = ok.status,
                files = ok.files,
                expiresAt = clock.nowMillis() + JOB_EXPIRES_AFTER_MS,
                detail = ok.detail,
            ),
        )
    }

    /** §G `queryStatus`: polls an existing provider job with an injected delay. */
    fun queryStatus(
        held: PluginCapability,
        providerJob: ProviderJob,
    ): PluginResult<ProviderJobStatus> {
        FixtureCapabilityGate
            .requirePairing<ProviderJobStatus>(held, "fixture.resolve.v0")
            ?.let { return it }
        if (clock.nowMillis() >= (providerJob.expiresAt ?: Long.MAX_VALUE)) {
            return pluginRejected(
                PluginErrorCode.LIMIT_EXCEEDED,
                "provider job expired; create a new job",
            )
        }
        return session.status
    }

    /** §G `selectFiles`: bounded file list under the file/size caps, or typed empty. */
    fun selectFiles(
        held: PluginCapability,
        providerJob: ProviderJob,
        expectedFileIds: List<String>,
    ): PluginResult<ResolveSession> {
        FixtureCapabilityGate
            .requirePairing<ResolveSession>(held, "fixture.resolve.v0")
            ?.let { return it }
        if (providerJob.status != ProviderJobStatus.READY) {
            return pluginRejected(
                PluginErrorCode.INVALID_ARGUMENT,
                "cannot select files from a job that is not READY",
            )
        }
        if (providerJob.files.isEmpty()) {
            return pluginRejected(PluginErrorCode.NOT_FOUND, "provider job exposed no files")
        }
        val first = providerJob.files.first()
        val exceedsCap = first.sizeBytes > JAIL_MAX_FILE_BYTES
        if (exceedsCap) {
            return pluginRejected(
                PluginErrorCode.LIMIT_EXCEEDED,
                "provider file ${first.name} exceeds the max file byte cap",
            )
        }
        return session.select(providerJob.files, expectedFileIds)
    }

    /** §G `resolveLink`: a short-lived expiring URL for one selected file. */
    fun resolveLink(
        held: PluginCapability,
        session: ResolveSession,
        releaseId: String,
    ): PluginResult<DownloadDescriptor> {
        FixtureCapabilityGate
            .requirePairing<DownloadDescriptor>(held, "fixture.resolve.v0")
            ?.let { return it }
        return FixtureResolverSession.resolveLink(session, releaseId, clock.nowMillis())
    }

    fun authRequired(): PluginResult<ProviderJob> = pluginAuthRequired("fixture provider authentication required; re-authorize")

    /** 429 with a provider `Retry-After` hint and a bounded backoff policy. */
    fun rateLimited(
        retryAfterMs: Long?,
        attempt: Int = 1,
    ): PluginResult<ProviderJob> {
        val rate = pluginRateLimited<ProviderJob>(retryAfterMs = retryAfterMs)
        rateLimitPolicy = RetryPolicy.forRateLimit(attempt, retryAfterMs)
        return rate
    }

    /** §G 429: expose the bounded policy for the host's retry scheduler. */
    fun rateLimitPolicyFor(
        attempt: Int,
        retryAfterMs: Long? = null,
    ): RetryPolicy = RetryPolicy.forRateLimit(attempt, retryAfterMs)

    /** Current bounded retry policy; the host reads this instead of looping. */
    val retryPolicy: RetryPolicy
        get() = rateLimitPolicy

    /** §G 401 rule: this marker forces [pluginAuthRequired], never a retry. */
    fun staleAuth(): PluginResult<ProviderJob> = authRequired()

    companion object {
        /** Hard file-size suffix to §G selectFiles, mirroring the schema's max-full-file cap. */
        const val MAX_FILE_BYTES = 50L * 1024L * 1024L
        private const val JAIL_MAX_FILE_BYTES = MAX_FILE_BYTES
        private const val JOB_EXPIRES_AFTER_MS = 3_600_000L
    }

    private var rateLimitPolicy: RetryPolicy =
        RetryPolicy.stopFor(PluginErrorCode.FORBIDDEN)
}
