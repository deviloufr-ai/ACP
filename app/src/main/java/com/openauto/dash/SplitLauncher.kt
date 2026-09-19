package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Opens an app in **split-screen** next to the dashboard so it stays visible
 * while the cards remain swipeable.
 *
 * `FLAG_ACTIVITY_LAUNCH_ADJACENT` is ignored on this head unit (it just opens
 * fullscreen), so with root we drive the split via `am`: dock the dashboard to
 * the primary pane, then launch the target into the secondary pane. Windowing
 * mode ints — 3 = SPLIT_SCREEN_PRIMARY, 4 = SPLIT_SCREEN_SECONDARY (Android ≤11),
 * 6 = MULTI_WINDOW (Android 12+).
 */
object SplitLauncher {

    fun launchAdjacent(context: Context, packageName: String): Boolean {
        val comp = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.resolveActivity(context.packageManager)?.flattenToShortString()
        val self = "${context.packageName}/.MainActivity"

        // Preferred: root-driven split. This unit reports Android 12 but behaves
        // like Android 10, so use the split-primary(3)/secondary(4) modes; if the
        // ROM ignores those, also try multi-window(6). Dock the dashboard first,
        // then launch the target into the other pane.
        if (comp != null) {
            val rootOk = runCatching {
                val script = buildString {
                    append("am start --windowingMode 3 -n $self; sleep 0.6; ")
                    append("am start --windowingMode 4 -n $comp; sleep 0.3; ")
                    append("am start --windowingMode 6 -n $comp")
                }
                Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() == 0
            }.onFailure { Log.d("SplitLauncher", "root split failed", it) }.getOrDefault(false)
            if (rootOk) return true
        }

        // Fallback (no root): app-level adjacent launch.
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        } ?: return false
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
