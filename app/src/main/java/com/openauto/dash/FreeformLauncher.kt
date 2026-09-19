package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log

/**
 * Launches an app in a **freeform floating window** at fixed [bounds], using the
 * head unit's built-in small-window / floating support. This puts the real
 * Google Maps app (with navigation) over its dashboard tile — no root, no API
 * key. The system still lets the user move/resize the window afterwards; this
 * just opens it at the tile's size and position.
 */
object FreeformLauncher {

    // ActivityOptions windowing-mode constants (hidden API values).
    private const val WINDOWING_MODE_FREEFORM = 5

    fun launchInBounds(context: Context, packageName: String, bounds: Rect): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            ?: return false

        val options = ActivityOptions.makeBasic()
        // Ask for freeform windowing mode so it opens as a movable window rather
        // than fullscreen. Hidden API — ignored on units that don't expose it.
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
        }.onFailure { Log.d("FreeformLauncher", "setLaunchWindowingMode unavailable", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { options.setLaunchBounds(bounds) }
        }

        return runCatching {
            context.startActivity(intent, options.toBundle())
            true
        }.onFailure { Log.e("FreeformLauncher", "freeform launch failed", it) }
            .getOrDefault(false)
    }
}
