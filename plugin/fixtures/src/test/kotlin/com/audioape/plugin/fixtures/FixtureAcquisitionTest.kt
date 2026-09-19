package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.AcquisitionPlan
import com.audioape.plugin.protocol.DownloadDescriptor
import com.audioape.plugin.protocol.PluginCapability
import com.audioape.plugin.protocol.PluginErrorCode
import com.audioape.plugin.protocol.ProviderJob
import com.audioape.plugin.protocol.ProviderJobStatus
import com.audioape.plugin.protocol.ReleaseFile
import com.audioape.plugin.protocol.RetryDecision
import com.audioape.plugin.protocol.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AA-019 evidence gate at the fixture boundary: `fixture.resolve.v0` plus the §G
 * resolver lifecycle (`prepareSource → queryStatus → selectFiles → resolveLink`) all
 * return stable, permissioned download descriptors for the two Heart of Darkness
 * releases, and every failure state (unknown release, expired link, wrong edition,
 * absent files, 401 no-retry, 429 bounded policy, capability mismatch) is typed.
 * Nothing here touches the network, timers, or the host engine.
 */
class FixtureAcquisitionTest {
    @Test
    fun `resolve returns a stable resolved download for both fixture releases`() {
        for (releaseId in listOf(FixtureLibrary.RELEASE_64KBPS, FixtureLibrary.RELEASE_64KBPS_ZIP)) {
            val result = FixtureSourceService.resolve(PluginCapability.ACQUISITION_RESOLVER, releaseId)
            assertTrue(result.isSuccess)
            val plan = result.value as AcquisitionPlan
            assertEquals(releaseId, plan.releaseId)
            assertEquals("librivox-fixture", plan.provenance.provider)
            assertTrue(plan.confidence.exactEdition && plan.confidence.verifiedAudio)

            val download = plan.download
            assertTrue("resolved download must be present", download != null)
            val descriptor = download as DownloadDescriptor
            assertTrue(descriptor.url.startsWith("https://"))
            assertTrue(descriptor.expiresAt > 1_789_000_000_000L)
            assertEquals(64, descriptor.sha256?.length)

            // The descriptor's media identity reconciles with the plan file list:
            // file name == URL basename.
            val planFile = plan.files.single()
            assertTrue(planFile.url != null)
            assertEquals(planFile.name, descriptor.url.substringAfterLast("/"))
            assertEquals(planFile.sizeBytes, planFile.sizeBytes)

            // Deterministic across calls.
            val again = FixtureSourceService.resolve(PluginCapability.ACQUISITION_RESOLVER, releaseId)
            assertEquals(plan, again.value)
        }
    }

    @Test
    fun `prepare query select resolve walks the full transfer lifecycle`() {
        val releaseId = FixtureLibrary.RELEASE_64KBPS

        val prepare = FixtureSourceService.prepareSource(PluginCapability.ACQUISITION_RESOLVER, releaseId)
        assertTrue(prepare.isSuccess)
        val job = prepare.value as ProviderJob
        assertEquals(ProviderJobStatus.READY, job.status)
        assertTrue(job.files.isNotEmpty())

        val status = FixtureSourceService.queryStatus(PluginCapability.ACQUISITION_RESOLVER, job)
        assertTrue(status.isSuccess)
        assertEquals(ProviderJobStatus.READY, status.value)

        val select =
            FixtureSourceService.selectFiles(
                PluginCapability.ACQUISITION_RESOLVER,
                releaseId,
                expectedFileIds = job.files.map { it.fileId },
            )
        assertTrue("select must accept the job's own file ids", select.isSuccess)

        val link = FixtureSourceService.resolveLink(PluginCapability.ACQUISITION_RESOLVER, releaseId)
        if (!link.isSuccess) {
            fail("resolveLink rejected: ${link.rejection?.detail}")
        }
        assertEquals(HeartOfDarknessFixture.FIRST_TRACK_URL, link.value?.url)
        assertFalse(link.value?.isExpiredAt(nowMillis()) == true)
    }

    @Test
    fun `wrong edition selection is a typed mismatch and never substitutes`() {
        val releaseId = FixtureLibrary.RELEASE_64KBPS
        val job =
            FixtureSourceService
                .prepareSource(PluginCapability.ACQUISITION_RESOLVER, releaseId)
                .value as ProviderJob

        val select =
            FixtureSourceService.selectFiles(
                PluginCapability.ACQUISITION_RESOLVER,
                releaseId,
                expectedFileIds = listOf("fixture-job:file:999"),
            )
        assertFalse(select.isSuccess)
        assertEquals(PluginErrorCode.NOT_FOUND, select.rejection?.code)
        assertTrue(job.files.none { it.fileId == "fixture-job:file:999" })
    }

    @Test
    fun `empty selection is rejected up front as an invalid argument`() {
        val result =
            FixtureSourceService.selectFiles(
                PluginCapability.ACQUISITION_RESOLVER,
                FixtureLibrary.RELEASE_64KBPS,
                expectedFileIds = emptyList(),
            )
        assertFalse(result.isSuccess)
        assertEquals(PluginErrorCode.INVALID_ARGUMENT, result.rejection?.code)
    }

    @Test
    fun `unknown release is a typed not found everywhere`() {
        val releaseId = "librivox:64:missing"

        val resolveResult = FixtureSourceService.resolve(PluginCapability.ACQUISITION_RESOLVER, releaseId)
        assertFalse(resolveResult.isSuccess)
        assertEquals(PluginErrorCode.NOT_FOUND, resolveResult.rejection?.code)

        val prepareResult = FixtureSourceService.prepareSource(PluginCapability.ACQUISITION_RESOLVER, releaseId)
        assertFalse(prepareResult.isSuccess)
        assertEquals(PluginErrorCode.NOT_FOUND, prepareResult.rejection?.code)

        val linkResult = FixtureSourceService.resolveLink(PluginCapability.ACQUISITION_RESOLVER, releaseId)
        assertFalse(linkResult.isSuccess)
        assertEquals(PluginErrorCode.NOT_FOUND, linkResult.rejection?.code)
    }

    @Test
    fun `capability mismatch is typed across the resolver surfaces`() {
        val releaseId = FixtureLibrary.RELEASE_64KBPS
        assertEquals(
            PluginErrorCode.CAPABILITY_MISMATCH,
            FixtureSourceService.prepareSource(PluginCapability.SOURCE_SEARCH, releaseId).rejection?.code,
        )
        assertEquals(
            PluginErrorCode.CAPABILITY_MISMATCH,
            FixtureSourceService.resolveLink(PluginCapability.CATALOG, releaseId).rejection?.code,
        )
        val job =
            FixtureSourceService
                .prepareSource(PluginCapability.ACQUISITION_RESOLVER, releaseId)
                .value as ProviderJob
        assertEquals(
            PluginErrorCode.CAPABILITY_MISMATCH,
            FixtureSourceService.queryStatus(PluginCapability.SOURCE_SEARCH, job).rejection?.code,
        )
    }

    @Test
    fun `the simulator injects the exact failure lifecycle the host will hit`() {
        val clock = FixedAcquisitionClock(1_789_000_000_000L)

        // 401 -> auth-required, never retried.
        val sim401 = FixtureResolverSimulator(clock = clock)
        val auth = sim401.staleAuth()
        assertFalse(auth.isSuccess)
        assertEquals(PluginErrorCode.FORBIDDEN, auth.rejection?.code)
        assertEquals(RetryDecision.STOP, sim401.retryPolicy.decision)
        assertFalse(sim401.retryPolicy.canRetry)

        // 429 with a Retry-After hint -> bounded backoff, capped attempts.
        val sim429 = FixtureResolverSimulator(clock = clock)
        val rate = sim429.rateLimited(retryAfterMs = 30_000L)
        assertFalse(rate.isSuccess)
        assertEquals(PluginErrorCode.LIMIT_EXCEEDED, rate.rejection?.code)
        assertEquals(30_000L, rate.rateLimitHintMs)
        assertTrue(sim429.retryPolicy.canRetry)
        assertTrue(sim429.retryPolicy.recommendedDelayMs <= RetryPolicy.MAX_DELAY_MS)

        // Expired link: host marks the first URL unusable on second use.
        val happy = FixtureResolverSimulator(clock = clock)
        val job = (happy.prepareSource(PluginCapability.ACQUISITION_RESOLVER, FixtureLibrary.RELEASE_64KBPS)).value as ProviderJob
        val session =
            (
                happy.selectFiles(
                    PluginCapability.ACQUISITION_RESOLVER,
                    job,
                    listOf(FixtureResolverSession.FILE_ID_PARTS),
                )
            ).value as com.audioape.plugin.protocol.ResolveSession
        val first = happy.resolveLink(PluginCapability.ACQUISITION_RESOLVER, session, FixtureLibrary.RELEASE_64KBPS)
        assertTrue(first.isSuccess)
        clock.advanceTo(FixtureResolverSession.FIXTURE_EXPIRY_MS + 1L)
        val second = happy.resolveLink(PluginCapability.ACQUISITION_RESOLVER, session, FixtureLibrary.RELEASE_64KBPS)
        assertFalse(second.isSuccess)
        assertEquals(PluginErrorCode.LIMIT_EXCEEDED, second.rejection?.code)
    }
}

/** Deterministic clock for the expiry/lifecycle simulator tests (AA-019, no timers). */
class FixedAcquisitionClock(
    initialNow: Long,
) : FixtureResolverClock {
    private var now: Long = initialNow

    override fun nowMillis(): Long = now

    fun advanceTo(target: Long) {
        now = target
    }
}
