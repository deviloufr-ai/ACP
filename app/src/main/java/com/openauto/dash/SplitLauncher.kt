package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Opens an app in **split-screen** next to the launcher, so it stays visible
 * while the dashboard is still usable. Uses `FLAG_ACTIVITY_LAUNCH_ADJACENT`
 * (the app-level way to enter split on a multi-window device); with root it also
 * nudges the split via `am` for units that ignore the flag from fullscreen.
 */
object SplitLauncher {

    fun launchAdjacent(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        } ?: return false

        val started = runCatching { context.startActivity(intent); true }.getOrDefault(false)

        // Best-effort root nudge: force the app into the secondary split pane on
        // units that ignore LAUNCH_ADJACENT from a fullscreen launcher.
        runCatching {
            val comp = context.packageManager.getLaunchIntentForPackage(packageName)
                ?.resolveActivity(context.packageManager)?.flattenToShortString()
            if (comp != null) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "am start --windowingMode 4 -n $comp"))
            }
        }.onFailure { Log.d("SplitLauncher", "root split nudge skipped", it) }

        return started
    }
}
