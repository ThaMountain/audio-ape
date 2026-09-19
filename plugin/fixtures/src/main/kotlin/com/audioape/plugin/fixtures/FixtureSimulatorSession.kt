package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.DownloadDescriptor
import com.audioape.plugin.protocol.PluginResult
import com.audioape.plugin.protocol.ProviderFile
import com.audioape.plugin.protocol.ProviderJobStatus
import com.audioape.plugin.protocol.ResolveSession
import com.audioape.plugin.protocol.pluginSuccess

/**
 * Injectable, deterministic behaviour envelope for one §G lifecycle surface, used by
 * [FixtureResolverSimulator]. Pure data, no network, no timers past the injected clock.
 */
data class FixtureSimulatorSession(
    val job: PluginResult<SimulatorJob> = FixtureSimulatorSession.happyJob(),
    val status: PluginResult<ProviderJobStatus> = pluginSuccess(ProviderJobStatus.READY),
    val select: (List<ProviderFile>, List<String>) -> PluginResult<ResolveSession> =
        { available, expected -> FixtureResolverSession.selectFor(FixtureSimulatorSession.DEFAULT_PROVIDER_JOB_ID, available, expected) },
    val resolve: (ResolveSession, String, Long) -> PluginResult<DownloadDescriptor> =
        FixtureResolverSession::resolveLink,
) {
    companion object {
        const val DEFAULT_PROVIDER_JOB_ID = "fixture-job:heart-of-darkness"

        fun happyJob(): PluginResult<SimulatorJob> =
            pluginSuccess(
                SimulatorJob(
                    status = ProviderJobStatus.READY,
                    files =
                        listOf(
                            ProviderFile(
                                fileId = FixtureResolverSession.FILE_ID_PARTS,
                                name = "heart_of_darkness_1a_conrad_64kb.mp3",
                                sizeBytes = 1_370_000L,
                            ),
                        ),
                ),
            )
    }
}

/**
 * Job-shaped outcome for the simulator's `prepareSource` step: status plus an optional
 * bounded file list. A `status` of ERROR carries no files; PENDING carries none yet.
 */
data class SimulatorJob(
    val status: ProviderJobStatus = ProviderJobStatus.READY,
    val files: List<ProviderFile> = emptyList(),
    val detail: String? = null,
)
