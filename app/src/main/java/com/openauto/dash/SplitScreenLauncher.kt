package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Drives Android's built-in split-screen so Maps and the last-used media app
 * run as two real, side-by-side system panes.
 *
 * How it works: Maps is launched as the base task, then the media app is
 * launched with [Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT], which the system places
 * in the adjacent pane. The launcher's own menu/info is drawn on top as a
 * [LauncherOverlayService] widget rather than occupying a third pane, because
 * Android's split-screen holds at most two apps and third-party apps (Maps,
 * Spotify, …) cannot be embedded inside another app's window.
 *
 * Requirements/caveats:
 * - The device must support split-screen multi-window. Some head units and
 *   Android TV builds do not, in which case the second launch simply opens
 *   fullscreen instead of adjacent.
 * - `LAUNCH_ADJACENT` behaviour is OEM-dependent; the short delay lets the base
 *   task settle before the adjacent launch.
 */
object SplitScreenLauncher {

    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val ADJACENT_LAUNCH_DELAY_MS = 500L

    /**
     * Opens Maps, then the last-used media app adjacent to it in split-screen.
     * Falls back to just Maps when no media app has been seen yet.
     */
    fun launchCockpit(context: Context) {
        context.startActivity(mapsIntent(context))

        val mediaPackage = CarMediaController.getLastMediaPackage(context) ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            val media = context.packageManager
                .getLaunchIntentForPackage(mediaPackage)
                ?.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            if (media != null) runCatching { context.startActivity(media) }
        }, ADJACENT_LAUNCH_DELAY_MS)
    }

    /** Launches [packageName] fullscreen (over the split). */
    fun launchFullscreen(context: Context, packageName: String): Boolean {
        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** Launch intent for the Maps app, or a generic geo intent as a fallback. */
    private fun mapsIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
