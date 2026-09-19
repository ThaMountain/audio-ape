package com.audioape.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

enum class ControllerAccess {
    APP,
    SYSTEM_OR_TRUSTED,
    DENIED,
}

/** Keeps mutation commands private while retaining the system notification controller. */
object ControllerAccessPolicy {
    fun decide(
        appPackageName: String,
        controllerPackageName: String,
        isTrusted: Boolean,
        isMediaNotificationController: Boolean,
    ): ControllerAccess =
        when {
            controllerPackageName == appPackageName -> ControllerAccess.APP
            isMediaNotificationController || isTrusted -> ControllerAccess.SYSTEM_OR_TRUSTED
            else -> ControllerAccess.DENIED
        }

    @OptIn(markerClass = [UnstableApi::class])
    fun playerCommands(access: ControllerAccess): Player.Commands {
        require(access != ControllerAccess.DENIED) { "denied controllers have no command set" }
        if (access == ControllerAccess.APP) {
            return MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
        }
        return MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            .buildUpon()
            .remove(Player.COMMAND_SET_MEDIA_ITEM)
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .remove(Player.COMMAND_SET_PLAYLIST_METADATA)
            .build()
    }
}
