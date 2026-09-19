package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.DeferredDownload
import com.audioape.plugin.protocol.DownloadDescriptor
import com.audioape.plugin.protocol.PluginErrorCode
import com.audioape.plugin.protocol.PluginResult
import com.audioape.plugin.protocol.ProviderFile
import com.audioape.plugin.protocol.ProviderJobStatus
import com.audioape.plugin.protocol.ResolveSession
import com.audioape.plugin.protocol.ResolveSessionStep
import com.audioape.plugin.protocol.pluginRejected
import com.audioape.plugin.protocol.pluginSuccess

/**
 * `session.*` primitives satisfy the fixture manifest's capability table §4 entry:
 * `resolver.debrid.job.v0` and `resolver.debrid.files.v0` plus the `fixture.resolve.v0`
 * acquisition step. Two namespaces:
 *
 * - `FixtureResolverSimulator` callers use [FixtureSimulatorSession] to inject whole-job
 *   outcomes (deterministic, expired/401/429/wrong-edition/absent shapes).
 * - Cooperative providers may emit a [DeferredDownload] that finishes inside the host
 *   engine's provider worker (AA-021) from that provider's own adapter, not here.
 */
object FixtureResolverSession {
    const val PROVIDER_JOB_PREFIX = "fixture-job"
    const val FILE_ID_PARTS = "fixture-file:parts"
    const val FILE_ID_ZIP = "fixture-file:zip"

    /** Canonical fixture download link: a bounded, clearly-labeled, expiring URL. */
    const val FIXTURE_RESOLVED_URL =
        "https://fixture.invalid/downloads/heart_of_darkness_1a_conrad_64kb.mp3"
    const val FIXTURE_RESOLVED_URL_ZIP = "https://fixture.invalid/downloads/heart_of_darkness.zip"
    const val FIXTURE_EXPIRY_MS = 1_798_646_400_000L
    const val FIXTURE_SHA256_V1 =
        "e7b5d8150c6573b5b308133577a5882a0e018d5e56686b150f1fb7df3715f0e0"

    fun resolveLink(
        session: ResolveSession,
        releaseId: String,
        now: Long,
    ): PluginResult<DownloadDescriptor> {
        if (now > FIXTURE_EXPIRY_MS) {
            return pluginRejected(
                PluginErrorCode.LIMIT_EXCEEDED,
                "fixture download link expired",
            )
        }
        return pluginSuccess(
            DownloadDescriptor(
                url = FIXTURE_RESOLVED_URL,
                expiresAt = FIXTURE_EXPIRY_MS,
                sha256 = FIXTURE_SHA256_V1,
            ),
        )
    }

    fun statusFor(providerJobId: String): PluginResult<ProviderJobStatus> = pluginSuccess(ProviderJobStatus.READY)

    /**
     * §G `prepareSource → queryStatus` job expiry: poll only while `now` is before the
     * job's own deadline. An expired job is a typed [PluginErrorCode.LIMIT_EXCEEDED]
     * (create a new job), never a silent re-prepare.
     */
    fun requireJobActive(
        providerJobId: String,
        expiresAt: Long?,
        now: Long,
    ): PluginResult<ProviderJobStatus>? {
        if (expiresAt != null && now >= expiresAt) {
            return pluginRejected(
                PluginErrorCode.LIMIT_EXCEEDED,
                "provider job expired; create a new job",
            )
        }
        return null
    }

    fun selectFor(
        providerJobId: String,
        available: List<ProviderFile>,
        expectedFileIds: List<String>,
    ): PluginResult<ResolveSession> {
        val expected = expectedFileIds.toSet()
        if (available.none { it.fileId in expected }) {
            return pluginRejected(
                PluginErrorCode.NOT_FOUND,
                "selected file ids do not match the provider file list",
            )
        }
        return pluginSuccess(
            ResolveSession(
                providerJobId = providerJobId,
                step = ResolveSessionStep.SELECT_FILES,
                matches = available.filter { it.fileId in expected },
            ),
        )
    }
}

/**
 * Typed rejection when the caller's expected id list cannot reconcile with the
 * provider file list (wrong edition / signed evidence mismatch). Empty selections are
 * rejected up front; the actual mismatch verdict always comes from [FixtureResolverSession.selectFor].
 */
fun requireExpectedFiles(expectedFileIds: List<String>): PluginResult<DeferredDownload>? {
    if (expectedFileIds.isEmpty()) {
        return pluginRejected(
            PluginErrorCode.INVALID_ARGUMENT,
            "expected file selection is empty",
        )
    }
    return null
}
