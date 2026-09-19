package com.audioape.player.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PlaybackNotificationPermission {
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun needsRuntimeRequest(
        sdkInt: Int,
        isGranted: Boolean,
    ): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !isGranted
}
