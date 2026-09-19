package com.audioape.player.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerAccessPolicyTest {
    @Test
    fun appAndSystemControllersAreAcceptedButUnknownCallerIsDenied() {
        assertEquals(
            ControllerAccess.APP,
            ControllerAccessPolicy.decide("com.audioape", "com.audioape", false, false),
        )
        assertEquals(
            ControllerAccess.SYSTEM_OR_TRUSTED,
            ControllerAccessPolicy.decide("com.audioape", "system.notification", false, true),
        )
        assertEquals(
            ControllerAccess.SYSTEM_OR_TRUSTED,
            ControllerAccessPolicy.decide("com.audioape", "trusted.auto", true, false),
        )
        assertEquals(
            ControllerAccess.DENIED,
            ControllerAccessPolicy.decide("com.audioape", "unknown.app", false, false),
        )
    }

    @Test
    fun systemControllersKeepTransportButCannotReplacePlaylist() {
        val appCommands = ControllerAccessPolicy.playerCommands(ControllerAccess.APP)
        val systemCommands = ControllerAccessPolicy.playerCommands(ControllerAccess.SYSTEM_OR_TRUSTED)

        assertTrue(appCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue(systemCommands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(systemCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(systemCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(systemCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(systemCommands.contains(Player.COMMAND_SET_PLAYLIST_METADATA))
    }
}
