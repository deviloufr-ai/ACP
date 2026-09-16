package com.openauto.dash

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Main Activity - Acts as the launcher for Android Head Units and smartphones.
 * Initializes the automotive dashboard with screen awake flags for driving safety.
 */
class MainActivity : ComponentActivity() {
    
    companion object {
        /** Singleton instance for global access */
        lateinit var instance: MainActivity
            private set
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Store singleton instance
        MainActivity.instance = this
        
        // Set window flags for driving safety (keep screen on)
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
 * Theme for the OpenAuto Dash application.
 * High-contrast dark theme optimized for driving safety.
 */
@Composable
fun OpenAutoDashTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}
