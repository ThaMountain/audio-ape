package com.audioape.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNotificationPermissionTest {
    @Test
    fun runtimeRequestOnlyAppliesToDeniedApi33AndLater() {
        assertFalse(PlaybackNotificationPermission.needsRuntimeRequest(32, false))
        assertTrue(PlaybackNotificationPermission.needsRuntimeRequest(33, false))
        assertFalse(PlaybackNotificationPermission.needsRuntimeRequest(36, true))
    }
}
