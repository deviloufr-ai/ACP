package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
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
    private const val ADJACENT_LAUNCH_DELAY_MS = 600L

    /**
     * Opens Maps on the left and the last-used media app on the right.
     *
     * Uses two mechanisms so at least one works per device: `LAUNCH_ADJACENT`
     * (Android's system split-screen) AND ActivityOptions launch bounds (freeform
     * multi-window, which many head units support and standard split does not).
     * Where neither is supported the apps open fullscreen one over the other —
     * enabling freeform on the head unit (see the app notes) makes the split work.
     */
    fun launchCockpit(context: Context) {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val leftHalf = Rect(0, 0, width / 2, height)
        val rightHalf = Rect(width / 2, 0, width, height)

        startInBounds(context, mapsIntent(context), leftHalf)

        val mediaPackage = CarMediaController.getLastMediaPackage(context) ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            val media = context.packageManager
                .getLaunchIntentForPackage(mediaPackage)
                ?.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            if (media != null) startInBounds(context, media, rightHalf)
        }, ADJACENT_LAUNCH_DELAY_MS)
    }

    /**
     * Starts [intent] positioned within [bounds]. On a head unit with freeform
     * multi-window the app is placed in that half of the screen; where it isn't
     * supported the bounds are ignored and the app opens fullscreen.
     */
    private fun startInBounds(context: Context, intent: Intent, bounds: Rect) {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { options.setLaunchBounds(bounds) }
        }
        runCatching { context.startActivity(intent, options.toBundle()) }
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
