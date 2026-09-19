package com.audioape.plugin.fixtures

import com.audioape.plugin.fixtures.SimulatorJob
import com.audioape.plugin.protocol.DownloadDescriptor
import com.audioape.plugin.protocol.PluginCapability
import com.audioape.plugin.protocol.PluginErrorCode
import com.audioape.plugin.protocol.PluginResult
import com.audioape.plugin.protocol.ProviderFile
import com.audioape.plugin.protocol.ProviderJob
import com.audioape.plugin.protocol.ProviderJobStatus
import com.audioape.plugin.protocol.ResolveSession
import com.audioape.plugin.protocol.ResolveSessionStep
import com.audioape.plugin.protocol.RetryDecision
import com.audioape.plugin.protocol.RetryPolicy
import com.audioape.plugin.protocol.pluginRejected
import com.audioape.plugin.protocol.pluginSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureResolverSimulatorTest {
    private val clock = FixedClock()

    @Test
    fun `happy path maps a fixture release to a provider job then an expiring link`() {
        val sim = FixtureResolverSimulator(clock = clock)
        val jobResult = sim.prepareSource(PluginCapability.ACQUISITION_RESOLVER, "librivox:64:parts:64kbps")

        assertTrue(jobResult.isSuccess)
        val job = jobResult.value as ProviderJob
        assertEquals(ProviderJobStatus.READY, job.status)
        assertTrue(job.files.isNotEmpty())
        assertEquals(FixtureResolverSession.FILE_ID_PARTS, job.files.first().fileId)

        val selectResult =
            sim.selectFiles(
                PluginCapability.ACQUISITION_RESOLVER,
                job,
                listOf(FixtureResolverSession.FILE_ID_PARTS),
            )
        assertTrue(selectResult.isSuccess)
        val session = selectResult.value as ResolveSession
        assertEquals(FixtureResolverSession.FILE_ID_PARTS, session.matches.single().fileId)

        val linkResult =
            sim.resolveLink(
                PluginCapability.ACQUISITION_RESOLVER,
                session,
                "librivox:64:parts:64kbps",
            )
        assertTrue(linkResult.isSuccess)
        val descriptor = linkResult.value as DownloadDescriptor
        assertEquals(FixtureResolverSession.FIXTURE_RESOLVED_URL, descriptor.url)
        assertEquals(FixtureResolverSession.FIXTURE_SHA256_V1, descriptor.sha256)
        assertTrue(descriptor.expiresAt > clock.nowMillis())
        assertFalse(descriptor.isExpiredAt(clock.nowMillis()))
    }

    @Test
    fun `resolved link is stable across two calls and survives the initial window`() {
        val sim = FixtureResolverSimulator(clock = clock)
        val job = (sim.prepareSource(PluginCapability.ACQUISITION_RESOLVER, "librivox:64:parts:64kbps")).value as ProviderJob
        val session =
            (
                sim.selectFiles(
                    PluginCapability.ACQUISITION_RESOLVER,
                    job,
                    listOf(FixtureResolverSession.FILE_ID_PARTS),
                )
            ).value as ResolveSession

        val first = sim.resolveLink(PluginCapability.ACQUISITION_RESOLVER, session, "librivox:64:parts:64kbps")
        val second = sim.resolveLink(PluginCapability.ACQUISITION_RESOLVER, session, "librivox:64:parts:64kbps")
        assertEquals(first.value, second.value)

        // At expiry the host must reject the second use of the same URL.
        clock.advanceTo(FixtureResolverSession.FIXTURE_EXPIRY_MS + 1L)
        val expired = sim.resolveLink(PluginCapability.ACQUISITION_RESOLVER, session, "librivox:64:parts:64kbps")
        assertFalse(expired.isSuccess)
        assertEquals(PluginErrorCode.LIMIT_EXCEEDED, expired.rejection?.code)
    }

    @Test
    fun `expired link is rejected on second use_ after expiry`() {
        val sim = FixtureResolverSimulator(clock = clock)
        val job = (sim.prepareSource(PluginCapability.ACQUISITION_RESOLVER, "librivox:64:parts:64kbps")).value as ProviderJob
        val session =
            (
                sim.selectFiles(
                    PluginCapability.ACQUISITION_RESOLVER,
                    job,
                    listOf(FixtureResolverSession.FILE_ID_PARTS),
                )
            ).value as ResolveSession

        clock.advanceTo(FixtureResolverSession.FIXTURE_EXPIRY_MS + 1L)
        val expired = sim.resolveLink(PluginCapability.ACQUISITION_RESOLVER, session, "librivox:64:parts:64kbps")
        assertFalse(expired.isSuccess)
        assertEquals(PluginErrorCode.LIMIT_EXCEEDED, expired.rejection?.code)
    }

    @Test
    fun `401 surfaces auth-required and never auto-retries`() {
        val sim = FixtureResolverSimulator(clock = clock)
        val result = sim.staleAuth()
        assertFalse(result.isSuccess)
        assertEquals(PluginErrorCode.FORBIDDEN, result.rejection?.code)

        val policy = sim.retryPolicy
        assertEquals(RetryDecision.STOP, policy.decision)
        assertFalse(policy.canRetry)
    }

    @Test
    fun `429 carries a bounded retry-after and policy, tightening at the cap`() {
        val sim = FixtureResolverSimulator(clock = clock)
        val result = sim.rateLimited(retryAfterMs = 20_000L)
        assertFalse(result.isSuccess)
        assertEquals(PluginErrorCode.LIMIT_EXCEEDED, result.rejection?.code)
        assertEquals(20_000L, result.rateLimitHintMs)

        val policy = sim.retryPolicy
        assertEquals(RetryDecision.RETRY_AFTER, policy.decision)
        assertEquals(20_000L, policy.recommendedDelayMs)
        assertTrue(policy.canRetry)

        // Hostile header is capped by the protocol's absolute maximum.
        val capped = RetryPolicy.forRateLimit(attempt = 1, retryAfterMs = RetryPolicy.MAX_DELAY_MS + 60_000L)
        assertEquals(RetryPolicy.MAX_DELAY_MS, capped.recommendedDelayMs)

        // Exhausted budget stops instead of retrying.
        val exhausted = RetryPolicy.forRateLimit(attempt = RetryPolicy.MAX_ATTEMPTS, retryAfterMs = 5_000L)
        assertEquals(RetryDecision.STOP, exhausted.decision)
    }

    @Test
    fun `wrong edition file list is a typed mismatch with no silent substitution`() {
        val sim = FixtureResolverSimulator(clock = clock)
        val job = (sim.prepareSource(PluginCapability.ACQUISITION_RESOLVER, "librivox:64:zip:64kbps")).value as ProviderJob

        // The caller expects the zip file but the job actually exposes the parts file.
        val selectResult =
            sim.selectFiles(
                PluginCapability.ACQUISITION_RESOLVER,
                job,
                listOf("some-other-file-id"),
            )
        assertFalse(selectResult.isSuccess)
        assertEquals(PluginErrorCode.NOT_FOUND, selectResult.rejection?.code)
    }

    @Test
    fun `absent files on a ready job are a typed empty result`() {
        val sim =
            FixtureResolverSimulator(
                session = FixtureSimulatorSession(job = fixtureJobWithoutFiles()),
                clock = clock,
            )
        val job = (sim.prepareSource(PluginCapability.ACQUISITION_RESOLVER, "librivox:64:parts:64kbps")).value as ProviderJob
        assertEquals(ProviderJobStatus.READY, job.status)

        val selectResult =
            sim.selectFiles(
                PluginCapability.ACQUISITION_RESOLVER,
                job,
                listOf(FixtureResolverSession.FILE_ID_PARTS),
            )
        assertFalse(selectResult.isSuccess)
        assertEquals(PluginErrorCode.NOT_FOUND, selectResult.rejection?.code)
    }

    @Test
    fun `unknown release prepare is a typed not-found on a rejecting job session`() {
        val rejectingSession =
            FixtureSimulatorSession(
                job = pluginRejected(PluginErrorCode.NOT_FOUND, "no such release"),
            )
        val sim = FixtureResolverSimulator(session = rejectingSession, clock = clock)
        val result = sim.prepareSource(PluginCapability.ACQUISITION_RESOLVER, "librivox:64:missing")
        assertFalse(result.isSuccess)
        assertEquals(PluginErrorCode.NOT_FOUND, result.rejection?.code)
    }

    @Test
    fun `capability mismatch is typed for every simulator surface`() {
        val sim = FixtureResolverSimulator(clock = clock)
        assertEquals(
            PluginErrorCode.CAPABILITY_MISMATCH,
            sim.prepareSource(PluginCapability.CATALOG, "librivox:64:parts:64kbps").rejection?.code,
        )
    }

    private fun fixtureJobWithoutFiles(): PluginResult<SimulatorJob> =
        pluginSuccess(
            SimulatorJob(
                status = ProviderJobStatus.READY,
                files = emptyList(),
            ),
        )
}

class FixedClock : FixtureResolverClock {
    private var now: Long = 1_789_000_000_000L

    override fun nowMillis(): Long = now

    fun advanceTo(target: Long) {
        now = target
    }
}
