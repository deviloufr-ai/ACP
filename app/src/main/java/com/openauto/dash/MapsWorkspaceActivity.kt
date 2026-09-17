package com.openauto.dash

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * Primary ("left") activity of the Drive workspace: the embedded Maps panel.
 *
 * On first creation it launches [MediaWorkspaceActivity]; the Activity
 * Embedding rule registered in [DashApplication] catches that launch and places
 * the two activities side by side instead of stacking them.
 */
class MapsWorkspaceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Launch the secondary pane once to form the embedded split.
        if (savedInstanceState == null) {
            startActivity(Intent(this, MediaWorkspaceActivity::class.java))
        }

        setContent {
            OpenAutoDashTheme {
                MapsPanel(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
