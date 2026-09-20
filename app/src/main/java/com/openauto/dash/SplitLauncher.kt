package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log

/**
 * Puts an app **beside the dashboard**, both as **freeform windows tiled
 * side-by-side** so neither covers the other (this ROM ignores AOSP split, and a
 * single freeform window just gets hidden behind the fullscreen launcher).
 *
 * With root we launch the target, then use `am task resize` to place the
 * dashboard on the left half and the target on the right half. Without root we
 * fall back to a single freeform window on the right (it can still be hidden
 * behind the dashboard on focus change).
 */
object SplitLauncher {

    private const val WINDOWING_MODE_FREEFORM = 5

    fun launchAdjacent(context: Context, packageName: String): Boolean {
        val comp = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.resolveActivity(context.packageManager)?.flattenToShortString() ?: return false
        val pkg = packageName
        val self = context.packageName
        val m = context.resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        val half = w / 2

        // Preferred: root — tile both tasks as freeform, non-overlapping.
        val rootOk = runCatching {
            val script = buildString {
                append("am start -n $comp; sleep 1.2; ")
                // Resolve task ids from the stack list, then resize each half.
                append("SELF=\$(am stack list 2>/dev/null | grep -m1 '$self/' | sed -n 's/.*taskId=\\([0-9][0-9]*\\).*/\\1/p'); ")
                append("MAPS=\$(am stack list 2>/dev/null | grep -m1 '$pkg/' | sed -n 's/.*taskId=\\([0-9][0-9]*\\).*/\\1/p'); ")
                append("[ -n \"\$MAPS\" ] && am task resize \$MAPS $half 0 $w $h; ")
                append("[ -n \"\$SELF\" ] && am task resize \$SELF 0 0 $half $h; ")
                append("echo done")
            }
            Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() == 0
        }.onFailure { Log.d("SplitLauncher", "root tile failed", it) }.getOrDefault(false)
        if (rootOk) return true

        // Fallback (no root): single freeform window on the right half.
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        } ?: return false
        val options = ActivityOptions.makeBasic()
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { options.setLaunchBounds(Rect(half, 0, w, h)) }
        }
        return runCatching { context.startActivity(intent, options.toBundle()); true }.getOrDefault(false)
    }
}
