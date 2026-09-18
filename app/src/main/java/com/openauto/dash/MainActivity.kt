package com.openauto.dash

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Entry point for OpenAuto Dash.
 *
 * Acts as the launcher/home surface. On launch it simply shows the dashboard
 * (main screen) — it does NOT auto-open Maps/media or force split-screen. The
 * user chooses what goes on the left/right panes from the dashboard (Cockpit
 * button and the per-slot widget pickers).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on and turn it on while the vehicle is running.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContent {
            OpenAutoDashTheme {
                AutomotiveDashboard()
            }
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
