package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log

/**
 * Opens an app **beside the dashboard** in a freeform window.
 *
 * This head unit ignores AOSP split-screen (`am --windowingMode 3/4` just opens
 * fullscreen — its SystemUI has no split divider), but it DOES support freeform
 * floating windows. So we launch the app in **freeform mode with launch bounds**
 * covering one half of the screen. The user can move/resize it like the unit's
 * native small-window.
 */
object SplitLauncher {

    private const val WINDOWING_MODE_FREEFORM = 5

    fun launchAdjacent(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        } ?: return false

        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        // Right half of the screen, leaving the dashboard visible on the left.
        val bounds = Rect(w / 2, 0, w, h)

        val options = ActivityOptions.makeBasic()
        // Request freeform windowing mode (hidden API) so it opens as a movable
        // window rather than fullscreen.
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
        }.onFailure { Log.d("SplitLauncher", "setLaunchWindowingMode unavailable", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { options.setLaunchBounds(bounds) }
        }

        return runCatching {
            context.startActivity(intent, options.toBundle())
            true
        }.onFailure { Log.e("SplitLauncher", "freeform launch failed", it) }.getOrDefault(false)
    }
}
