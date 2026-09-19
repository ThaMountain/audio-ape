package com.audioape.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.audioape.core.database.AudioApeDatabase
import com.audioape.player.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

@OptIn(markerClass = [UnstableApi::class])
class AudioApePlaybackService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var database: AudioApeDatabase
    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private var coldRestoreJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        database = PlaybackServiceDependencies.databaseFactory(this)
        player =
            ExoPlayer
                .Builder(this)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    playWhenReady = false
                    addListener(focusLossListener)
                }

        mediaLibrarySession =
            MediaLibrarySession
                .Builder(this, player, sessionCallback)
                .setSessionActivity(mainActivityPendingIntent())
                .build()

        coldRestoreJob = serviceScope.launch { restoreLatestPlaylistPaused() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaLibrarySession

    override fun onDestroy() {
        coldRestoreJob?.cancel()
        serviceScope.cancel()
        player.removeListener(focusLossListener)
        player.release()
        mediaLibrarySession.release()
        database.close()
        super.onDestroy()
    }

    private val focusLossListener =
        object : Player.Listener {
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (
                    playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                    player.playWhenReady
                ) {
                    player.pause()
                }
            }
        }

    private val sessionCallback =
        object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val access =
                    ControllerAccessPolicy.decide(
                        appPackageName = packageName,
                        controllerPackageName = controller.packageName,
                        isTrusted = controller.isTrusted,
                        isMediaNotificationController = session.isMediaNotificationController(controller),
                    )
                if (access == ControllerAccess.DENIED) return MediaSession.ConnectionResult.reject()

                return MediaSession.ConnectionResult.accept(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
                    ControllerAccessPolicy.playerCommands(access),
                )
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                coldRestoreJob?.cancel()
                player.pause()
                val decoded = PlaybackMetadata.decode(mediaItems)
                if (decoded == null) {
                    return completedFuture(
                        MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs),
                    )
                }

                return serviceFuture("restore ${decoded.bookId.value}") {
                    val checkpoint =
                        withContext(Dispatchers.IO) {
                            database.playbackCheckpointDao().checkpoint(decoded.bookId)
                        }
                    val restore =
                        PlaybackRestorePlanner.restore(decoded.bookId, decoded.timeline, checkpoint)
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems,
                        restore.mediaItemIndex,
                        restore.positionMs,
                    )
                }
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> = completedFuture(LibraryResult.ofItem(BROWSE_ROOT, params))

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> =
                if (parentId == BROWSE_ROOT_ID) {
                    completedFuture(LibraryResult.ofItemList(emptyList(), params))
                } else {
                    completedFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params))
                }
        }

    private suspend fun restoreLatestPlaylistPaused() {
        val restored =
            withContext(Dispatchers.IO) {
                val checkpoint = database.playbackCheckpointDao().latestCheckpoint() ?: return@withContext null
                val book = database.libraryBookDao().book(checkpoint.bookId) ?: return@withContext null
                val parts = database.mediaPartDao().parts(checkpoint.bookId)
                if (parts.isEmpty()) return@withContext null
                val playlist =
                    PlaybackPlaylistFactory.createFromEntities(
                        bookTitle = book.displayTitle,
                        parts = parts,
                    )
                val start =
                    PlaybackRestorePlanner.restore(checkpoint.bookId, playlist.timeline, checkpoint)
                RestoredPlaylist(playlist, start)
            } ?: return

        if (player.mediaItemCount != 0) return
        player.playWhenReady = false
        player.setMediaItems(
            restored.playlist.mediaItems,
            restored.start.mediaItemIndex,
            restored.start.positionMs,
        )
        player.prepare()
    }

    private fun mainActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun <T> completedFuture(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "completed Audio Ape media future"
        }

    private fun <T> serviceFuture(
        label: String,
        block: suspend () -> T,
    ): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            val job =
                serviceScope.launch {
                    runCatching { block() }
                        .onSuccess(completer::set)
                        .onFailure(completer::setException)
                }
            completer.addCancellationListener({ job.cancel() }, DIRECT_EXECUTOR)
            label
        }

    private data class RestoredPlaylist(
        val playlist: PlaybackPlaylist,
        val start: PlaybackStart,
    )

    private companion object {
        const val BROWSE_ROOT_ID = "audio_ape_root"
        val DIRECT_EXECUTOR = Executor(Runnable::run)
        val BROWSE_ROOT =
            MediaItem
                .Builder()
                .setMediaId(BROWSE_ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle("Audio Ape")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build(),
                ).build()
    }
}
