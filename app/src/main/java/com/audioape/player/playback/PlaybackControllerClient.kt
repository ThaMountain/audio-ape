package com.audioape.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlayerState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val currentPartIndex: Int = 0,
    val partPositionMs: Long = 0L,
    val bookPositionMs: Long? = null,
    val durationMs: Long? = null,
    val hasNext: Boolean = false,
    val errorMessage: String? = null,
)

/** Activity-facing controller facade. It never exposes the service-owned Player. */
class PlaybackControllerClient(
    context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlayerState())
    private var controller: MediaController? = null
    private var connectionJob: Job? = null
    private var activePlaylist: PlaybackPlaylist? = null

    val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                publishState(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                mutableState.value = mutableState.value.copy(errorMessage = error.message)
            }
        }

    suspend fun connect() {
        if (controller?.isConnected == true) return
        val token = SessionToken(appContext, ComponentName(appContext, AudioApePlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        val connected =
            suspendCancellableCoroutine { continuation ->
                future.addListener(
                    {
                        runCatching { future.get() }
                            .onSuccess(continuation::resume)
                            .onFailure(continuation::resumeWithException)
                    },
                    ContextCompat.getMainExecutor(appContext),
                )
                continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
        controller = connected
        connected.addListener(listener)
        publishState(connected)
        connectionJob?.cancel()
        connectionJob =
            scope.launch {
                while (isActive) {
                    publishState(connected)
                    delay(POSITION_POLL_INTERVAL_MS)
                }
            }
    }

    fun loadBook(playlist: PlaybackPlaylist) {
        activePlaylist = playlist
        withController { mediaController ->
            mediaController.setMediaItems(playlist.mediaItems, true)
            mediaController.prepare()
        }
    }

    fun play() = withController(MediaController::play)

    fun pause() = withController(MediaController::pause)

    fun seekTo(bookPositionMs: Long) {
        val partPosition = activePlaylist?.timeline?.toPartPosition(bookPositionMs) ?: return
        withController { it.seekTo(partPosition.partIndex, partPosition.localOffsetMilliseconds) }
    }

    fun next() = withController(MediaController::seekToNextMediaItem)

    override fun close() {
        connectionJob?.cancel(CancellationException("controller closed"))
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
        mutableState.value = PlayerState()
    }

    private fun withController(command: (MediaController) -> Unit) {
        controller?.takeIf(MediaController::isConnected)?.let(command)
    }

    private fun publishState(player: Player) {
        val timeline =
            activePlaylist?.timeline
                ?: PlaybackMetadata
                    .decode(
                        List(player.mediaItemCount) { player.getMediaItemAt(it) },
                    )?.timeline
        val partIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val partPosition = player.currentPosition.coerceAtLeast(0L)
        mutableState.value =
            PlayerState(
                isConnected = true,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                playbackState = player.playbackState,
                currentPartIndex = partIndex,
                partPositionMs = partPosition,
                bookPositionMs =
                    timeline?.let {
                        runCatching { it.toBookPosition(partIndex, partPosition) }.getOrNull()
                    },
                durationMs = timeline?.totalDurationMilliseconds,
                hasNext = player.hasNextMediaItem(),
                errorMessage = player.playerError?.message,
            )
    }

    private companion object {
        const val POSITION_POLL_INTERVAL_MS = 500L
    }
}
