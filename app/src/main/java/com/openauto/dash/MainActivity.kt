package com.openauto.dash

import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Entry point for OpenAuto Dash — the launcher/home surface. Runs edge-to-edge
 * in immersive fullscreen (status/navigation bars hidden), showing the dashboard.
 */
class MainActivity : ComponentActivity() {

    // Whether the launcher is sharing the screen (split-screen / freeform). The
    // dashboard collapses to a single widget in this state. configChanges keeps
    // the activity alive across the transition, so we drive it via a Compose
    // state updated from onMultiWindowModeChanged.
    private val inMultiWindow = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on and turn it on while the vehicle is running.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        inMultiWindow.value = isInMultiWindowMode
        enableImmersiveFullscreen()

        setContent {
            OpenAutoDashTheme {
                AutomotiveDashboard(inSplitMode = inMultiWindow.value)
            }
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        inMultiWindow.value = isInMultiWindowMode
    }

    // Some head-unit ROMs don't reliably deliver onMultiWindowModeChanged, so
    // also re-check on resume and on the config change that entering split fires.
    override fun onResume() {
        super.onResume()
        inMultiWindow.value = isInMultiWindowMode
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        inMultiWindow.value = isInMultiWindowMode
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide the system bars whenever we regain focus (they can reappear
        // after a transient swipe or returning from another app).
        if (hasFocus) enableImmersiveFullscreen()
        // Focus changes accompany entering/leaving split on some ROMs.
        inMultiWindow.value = isInMultiWindowMode
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * High-contrast dark theme tuned for glare-free reading while driving.
 */
@Composable
fun OpenAutoDashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF5B8DEF),
            secondary = Color(0xFF2DD4BF),
            background = Color(0xFF0B0D10),
            surface = Color(0xFF15181E),
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
