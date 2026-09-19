package com.audioape.player.playback

import com.audioape.core.database.PlaybackCheckpointEntity
import com.audioape.core.model.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

internal data class PlaybackCheckpointSnapshot(
    val bookId: BookId,
    val positionMs: Long,
    val speed: Float,
)

/**
 * Coalesces playback events into small atomic checkpoint batches.
 *
 * Event methods are called from the service main thread. The immutable snapshot is captured when
 * the event occurs, so a delayed batch write cannot accidentally persist a later player position.
 */
internal class PlaybackCheckpointRecorder(
    private val scope: CoroutineScope,
    private val checkpointProvider: () -> PlaybackCheckpointSnapshot?,
    private val saveCheckpoints: suspend (List<PlaybackCheckpointEntity>) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val periodicIntervalMs: Long = DEFAULT_PERIODIC_INTERVAL_MS,
    private val batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS,
    private val onWriteFailure: (Throwable) -> Unit = {},
) {
    private val pending = linkedMapOf<BookId, PlaybackCheckpointEntity>()
    private var flushJob: Job? = null
    private var periodicJob: Job? = null
    private var activelyPlaying = false
    private var playWhenReady = false
    private var stopped = false

    init {
        require(periodicIntervalMs > 0L) { "periodic interval must be positive" }
        require(batchWindowMs >= 0L) { "batch window must be nonnegative" }
    }

    fun onPlayWhenReadyChanged(isReadyToPlay: Boolean) {
        if (playWhenReady && !isReadyToPlay) enqueueCurrentCheckpoint()
        playWhenReady = isReadyToPlay
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        activelyPlaying = isPlaying
        if (isPlaying) {
            startPeriodicWrites()
        } else {
            periodicJob?.cancel()
            periodicJob = null
        }
    }

    fun onSeekCommitted() {
        enqueueCurrentCheckpoint()
    }

    fun onMediaItemTransition() {
        if (activelyPlaying) enqueueCurrentCheckpoint()
    }

    /** Stops scheduled work and returns a final, latest-per-book batch for synchronous shutdown. */
    fun stopAndTakeFinalBatch(): List<PlaybackCheckpointEntity> {
        if (stopped) return emptyList()
        stopped = true
        periodicJob?.cancel()
        periodicJob = null
        flushJob?.cancel()
        flushJob = null
        captureCurrentCheckpoint()?.let { pending[it.bookId] = it }
        return pending.values.toList().also { pending.clear() }
    }

    /** Writes the final shutdown snapshot after scheduled recorder work has been cancelled. */
    suspend fun writeFinalBatch(batch: List<PlaybackCheckpointEntity>): Boolean {
        if (batch.isEmpty()) return true
        return runCatching {
            saveCheckpoints(batch)
        }.fold(
            onSuccess = { true },
            onFailure = {
                onWriteFailure(it)
                false
            },
        )
    }

    private fun startPeriodicWrites() {
        if (stopped || periodicJob?.isActive == true) return
        periodicJob =
            scope.launch {
                while (isActive) {
                    delay(periodicIntervalMs)
                    if (activelyPlaying) enqueueCurrentCheckpoint()
                }
            }
    }

    private fun enqueueCurrentCheckpoint() {
        if (stopped) return
        val checkpoint = captureCurrentCheckpoint() ?: return
        pending[checkpoint.bookId] = checkpoint
        scheduleFlush()
    }

    private fun captureCurrentCheckpoint(): PlaybackCheckpointEntity? {
        val snapshot = checkpointProvider() ?: return null
        return PlaybackCheckpointEntity(
            bookId = snapshot.bookId,
            positionMs = snapshot.positionMs,
            lastPlayedAt = now(),
            speed = snapshot.speed,
            lastPlayingIntent = false,
        )
    }

    private fun scheduleFlush() {
        if (stopped || flushJob?.isActive == true) return
        flushJob =
            scope.launch {
                var writeSucceeded = true
                try {
                    delay(batchWindowMs)
                    writeSucceeded = flushPending()
                } finally {
                    flushJob = null
                    if (writeSucceeded && !stopped && pending.isNotEmpty()) scheduleFlush()
                }
            }
    }

    private suspend fun flushPending(): Boolean {
        val batch = pending.values.toList()
        pending.clear()
        if (batch.isEmpty()) return true

        return runCatching {
            saveCheckpoints(batch)
            true
        }.onFailure { failure ->
            batch.forEach { failed -> pending.putIfAbsent(failed.bookId, failed) }
            onWriteFailure(failure)
        }.getOrDefault(false)
    }

    private companion object {
        const val DEFAULT_PERIODIC_INTERVAL_MS = 10_000L
        const val DEFAULT_BATCH_WINDOW_MS = 250L
    }
}
