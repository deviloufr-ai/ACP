package com.openauto.dash

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import java.lang.reflect.Method

/**
 * Drives Android's built-in split-screen so Maps and the last-used media app
 * run as two real, side-by-side system panes.
 * 
 * Optimized for ROCO QF001/K706 head units which support manual multi-window
 * but may ignore FLAG_ACTIVITY_LAUNCH_ADJACENT.
 *
 * How it works: Both apps are launched simultaneously with proper bounds,
 * then the split is activated via reflection API, custom broadcasts, or
 * system properties used by these head units.
 */
object SplitScreenLauncher {

    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    
    // FYT/ROCO specific constants for split-screen trigger
    private const val ROCO_SPLIT_ACTION = "com.syu.ms.split"
    private const val SYS_SPLIT_SCREEN = "sys_split_screen"

    /**
     * Opens Maps on the left and the last-used media app on the right.
     * 
     * Optimized for ROCO K706: Uses simultaneous launch + split activation.
     */
    fun launchCockpit(context: Context, targetSecondaryPackage: String? = null) {
        val leftPkg = MAPS_PACKAGE
        val rightPkg = targetSecondaryPackage ?: CarMediaController.getLastMediaPackage(context) ?: "com.android.chrome"
        launchCustomSplit(context, leftPkg, rightPkg)
    }

    /**
     * Custom split mode allowing any two installed applications to be placed side-by-side.
     */
    fun launchCustomSplit(context: Context, leftPackage: String, rightPackage: String) {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val leftHalf = Rect(0, 0, width / 2, height)
        val rightHalf = Rect(width / 2, 0, width, height)

        val leftIntent = context.packageManager.getLaunchIntentForPackage(leftPackage)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            } ?: return
        val rightIntent = context.packageManager.getLaunchIntentForPackage(rightPackage)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            } ?: return

        // 1. Launch Primary (Left) App
        launchActivityInBounds(context, leftIntent, leftHalf)

        // 2. Short pause to let first app initialize
        Handler(Looper.getMainLooper()).postDelayed({
            // 3. Launch Secondary (Right) App
            launchActivityInBounds(context, rightIntent, rightHalf)

            // 4. Trigger system split overlay sync activation
            Handler(Looper.getMainLooper()).postDelayed({
                tryToActivateSplit(context, leftIntent, rightIntent)
            }, 400L)
        }, 400L)
    }

    /**
     * Attempts to activate split-screen after both activities are launched.
     * Uses reflection and custom broadcasts for ROCO QF001 compatibility.
     */
    private fun tryToActivateSplit(context: Context, leftIntent: Intent, rightIntent: Intent) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) 
            as? ActivityManager ?: return

        val leftPkg = leftIntent.component?.packageName ?: leftIntent.`package` ?: ""
        val rightPkg = rightIntent.component?.packageName ?: rightIntent.`package` ?: ""

        // 1. Trigger ROCO/FYT specific broadcast with layout intent extras
        runCatching {
            val intent = Intent(ROCO_SPLIT_ACTION).apply {
                putExtra("package1", leftPkg)
                putExtra("package2", rightPkg)
                putExtra("split_mode", 1)
            }
            context.sendBroadcast(intent)
        }

        // 2. Update system property used by some FYT firmware
        runCatching {
            Settings.System.putInt(context.contentResolver, SYS_SPLIT_SCREEN, 1)
        }

        // 3. Reflection: Tenter d'appeler splitScreenRequested() (Standard or Vendor)
        var success = false
        runCatching {
            val method: Method = activityManager::class.java
                .getMethod("splitScreenRequested", Intent::class.java, Intent::class.java)
                .apply { isAccessible = true }

            // Lancer le split en spécifiant l'ordre: leftIntent (primary/left), rightIntent (secondary/right)
            method.invoke(activityManager, leftIntent, rightIntent)
            success = true
        }

        // 4. Alternative reflection for Android 10/11 system services
        if (!success) {
            runCatching {
                // FYT-specific: Some units use a different reflection method or a system property
                // that is already handled in step 2. We could try to get task IDs here if needed,
                // but the broadcast is usually the primary trigger on ROCO units.
            }
        }
    }

    /**
     * Attempts to use ActivityOptions bounds for launching an activity.
     */
    private fun launchActivityInBounds(context: Context, intent: Intent, bounds: Rect) {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { options.setLaunchBounds(bounds) }
        }
        // Utiliser les bounds pour positionner l'activité
        context.startActivity(intent, options.toBundle())
    }

    /** Starts [intent] positioned within [bounds]. */
    private fun startInBounds(context: Context, intent: Intent, bounds: Rect) {
        launchActivityInBounds(context, intent, bounds)
    }

    /** Launch intent for the Maps app, or a generic geo intent as fallback. */
    private fun mapsIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                // Remove MULTIPLE_TASK as it can cause black screens if the app is already running
            }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Launches the app identified by [packageName] in fullscreen mode.
     * Overrides any existing split-screen layout.
     */
    fun launchFullscreen(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return

        val metrics = context.resources.displayMetrics
        val fullScreen = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        launchActivityInBounds(context, intent, fullScreen)
    }
}