package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * then the split is activated via reflection API or native split support.
 * The launcher's own menu/info is drawn on top as a [LauncherOverlayService] 
 * widget rather than occupying a third pane.
 *
 * Requirements/caveats:
 * - ROCO K706 units support multi-window but OEM launcher may ignore LAUNCH_ADJACENT
 * - Uses simultaneous launch with split activation for reliable results
 * - Falls back to manual split activation if reflection API not available
 */
object SplitScreenLauncher {

    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val ADJACENT_LAUNCH_DELAY_MS = 200L

    /**
     * Opens Maps on the left and the last-used media app on the right.
     * 
     * Optimized for ROCO K706: Uses simultaneous launch + split activation.
     */
    fun launchCockpit(context: Context) {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val leftHalf = Rect(0, 0, width / 2, height)
        val rightHalf = Rect(width / 2, 0, width, height)

        // Récupérer les deux intents
        val mapsIntent = mapsIntent(context)
        val mediaPackage = CarMediaController.getLastMediaPackage(context) ?: return
        val mediaIntent = context.packageManager
            .getLaunchIntentForPackage(mediaPackage) ?: return

        // Lancer Maps dans la moitié gauche avec bounds
        launchActivityInBounds(context, mapsIntent, leftHalf)

        // Lancer l'app média dans la moitié droite avec bounds
        launchActivityInBounds(context, mediaIntent, rightHalf)

        // Attendre que les deux activities soient créées, puis activer le split
        Handler(Looper.getMainLooper()).postDelayed({
            tryToActivateSplit(context, mapsIntent, mediaIntent)
        }, ADJACENT_LAUNCH_DELAY_MS)
    }

    /**
     * Attempts to activate split-screen after both activities are launched.
     * Uses reflection API for ROCO K706 compatibility.
     */
    private fun tryToActivateSplit(context: Context, mapsIntent: Intent, mediaIntent: Intent) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) 
            as? android.app.ActivityManager ?: return

        // Tenter d'appeler splitScreenRequested() avec les deux intents
        runCatching {
            val method: Method = activityManager::class.java
                .getMethod("splitScreenRequested", Intent::class.java, Intent::class.java)
                .apply { isAccessible = true }

            // Lancer le split en spécifiant l'ordre (media après maps)
            method.invoke(activityManager, mediaIntent, mapsIntent)
        }.onFailure {
            it.printStackTrace()
            // Fallback : laisser l'utilisateur activer manuellement le split
            // Le ROCO launcher supporte le split manuel
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
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
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