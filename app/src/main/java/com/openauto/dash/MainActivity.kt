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
 * Acts as the launcher/home surface for Android head units and phone mounts.
 * Keeps the screen awake while driving and hands off to the Compose dashboard.
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
            primary = Color(0xFF6750A4),
            secondary = Color(0xFF03DAC5),
            background = Color(0xFF0F1115),
            surface = Color(0xFF1C1E24),
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
