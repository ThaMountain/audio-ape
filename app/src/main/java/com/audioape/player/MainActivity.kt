package com.audioape.player

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.audioape.player.playback.PlaybackControllerClient
import com.audioape.player.playback.PlaybackNotificationPermission
import com.audioape.player.ui.theme.AudioApeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val playbackController by lazy { PlaybackControllerClient(this) }
    private var pendingNotificationPermissionAction: (() -> Unit)? = null
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Media-session notifications are permission-exempt. Denial affects other app
            // notifications but must not turn an explicit playback request into a no-op.
            pendingNotificationPermissionAction?.invoke()
            pendingNotificationPermissionAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch { runCatching { playbackController.connect() } }

        setContent {
            AudioApeTheme {
                AudioApeShell(versionName = BuildConfig.VERSION_NAME)
            }
        }
    }

    /** Thin hook for the future player UI's explicit play action. */
    internal fun withPlaybackNotificationPermission(action: () -> Unit) {
        if (PlaybackNotificationPermission.isGranted(this)) {
            action()
            return
        }
        pendingNotificationPermissionAction = action
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        playbackController.close()
        super.onDestroy()
    }
}

@Composable
internal fun AudioApeShell(versionName: String) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Audio Ape",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = ShellVersion.label(versionName),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF060B12)
@Composable
private fun AudioApeShellPreview() {
    AudioApeTheme {
        AudioApeShell(versionName = "0.1.0")
    }
}
