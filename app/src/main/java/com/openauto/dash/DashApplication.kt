package com.openauto.dash

import android.app.Application
import android.content.ComponentName
import androidx.window.embedding.RuleController
import androidx.window.embedding.SplitAttributes
import androidx.window.embedding.SplitPairFilter
import androidx.window.embedding.SplitPairRule

/**
 * Registers Jetpack WindowManager Activity Embedding split rules at startup.
 *
 * Each "workspace" is a pair of the app's own activities shown side by side.
 * WindowManager keeps both embedded activities alive simultaneously and lays
 * them out at the ratio declared here (50/50), without the OS killing either.
 *
 * Only activities the app owns can be embedded — third-party apps such as Maps
 * or Spotify do not opt in to embedding, so those still open fullscreen or via
 * the system split-screen cockpit ([SplitScreenLauncher]).
 */
class DashApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerWorkspaceSplits()
    }

    private fun registerWorkspaceSplits() {
        val evenSplit = SplitAttributes.Builder()
            .setSplitType(SplitAttributes.SplitType.ratio(0.5f))
            .build()

        // Workspace 1 — Drive: Maps (left) + media now-playing (right).
        val driveWorkspace = SplitPairRule.Builder(
            setOf(
                SplitPairFilter(
                    ComponentName(this, MapsWorkspaceActivity::class.java),
                    ComponentName(this, MediaWorkspaceActivity::class.java),
                    null
                )
            )
        )
            .setDefaultSplitAttributes(evenSplit)
            // Split at any width (default is 600dp, which would stack on phones).
            .setMinWidthDp(0)
            .setMinSmallestWidthDp(0)
            .build()

        RuleController.getInstance(this).addRule(driveWorkspace)
    }
}
