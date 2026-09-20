package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log

/**
 * Opens an app in a **freeform floating window**.
 *
 * This head unit's ROM ignores AOSP split-screen and blocks `am task resize`
 * ("resizeTask not allowed"), so we can't tile two app windows side-by-side.
 * The best it allows is launching the app in freeform mode — it appears as a
 * movable/resizable floating window (the system picks the position; our bounds
 * are a hint it may ignore). For a map that stays put while the dashboard is
 * used, prefer the in-app MapLibre map widget instead.
 */
object SplitLauncher {

    private const val WINDOWING_MODE_FREEFORM = 5

    fun launchAdjacent(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        } ?: return false

        val m = context.resources.displayMetrics
        val bounds = Rect(m.widthPixels / 2, 0, m.widthPixels, m.heightPixels)

        val options = ActivityOptions.makeBasic()
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
