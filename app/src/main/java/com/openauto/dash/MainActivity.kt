package com.openauto.dash

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Entry point for Dashwheel — the launcher/home surface. Runs edge-to-edge
 * in immersive fullscreen (status/navigation bars hidden), showing the dashboard.
 */
class MainActivity : ComponentActivity() {

    // Whether the launcher is sharing the screen (split-screen / freeform). The
    // dashboard collapses to a single widget in this state. configChanges keeps
    // the activity alive across the transition, so we drive it via a Compose
    // state updated from onMultiWindowModeChanged.
    private val inMultiWindow = mutableStateOf(false)

    // The language picked in the launcher, if any, over the system's.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

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
            // An app window (docked or parked aside) forces the status bar on.
            val windowOpen = PipAnchor.dockedPackages.collectAsState().value.isNotEmpty()
            LaunchedEffect(windowOpen) {
                statusBarForced = windowOpen
                enableImmersiveFullscreen()
            }
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

    /**
     * True while an app window exists, docked on its tile or parked aside:
     * Android then shows the status bar whatever the dashboard asks. Asking
     * to hide it anyway (as the dashboard did whenever it had the focus, i.e.
     * with the window parked aside) made the head unit draw its own flat white
     * bar; not asking lets the bar show the dashboard's colours through it.
     */
    private var statusBarForced = false

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (statusBarForced) {
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
            }
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * Follows the dashboard's dark or light version (the Auto / Dark / Light
 * switch; Auto tracks the car's light sensor): a high-contrast dark scheme at
 * night for glare-free reading, a bright scheme by day so the UI stays legible
 * in sunlight.
 */
@Composable
fun OpenAutoDashTheme(content: @Composable () -> Unit) {
    val colorScheme = if (!DashColors.Light) {
        darkColorScheme(
            primary = Color(0xFF5B8DEF),
            secondary = Color(0xFF2DD4BF),
            background = Color(0xFF0B0D10),
            surface = Color(0xFF15181E),
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1A73E8),
            secondary = Color(0xFF0FA895),
            background = Color(0xFFF1F3F4),
            surface = Color(0xFFFFFFFF),
            onPrimary = Color.White,
            onBackground = Color(0xFF202124),
            onSurface = Color(0xFF202124)
        )
    }
    // The status bar is see-through (themes.xml), so the dashboard's background
    // shows behind it: its icons go dark on a day theme, white on a night one.
    val view = LocalView.current
    val light = DashColors.Light
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = light
        }
    }
    // The driving type scale (DashType.kt): nothing under 14 sp, labels at 16 sp.
    MaterialTheme(colorScheme = colorScheme, typography = DashTypography, content = content)
}
