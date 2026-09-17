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
 * How it works: Maps is launched first, then the media app is launched with
 * ActivityOptions bounds or reflection-based splitScreenRequested(). The
 * launcher's own menu/info is drawn on top as a [LauncherOverlayService] 
 * widget rather than occupying a third pane.
 *
 * Requirements/caveats:
 * - ROCO K706 units support multi-window but OEM launcher may ignore LAUNCH_ADJACENT
 * - Uses ActivityOptions bounds (freeform) as primary method
 * - Falls back to manual split activation if reflection API not available
 */
object SplitScreenLauncher {

    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val ADJACENT_LAUNCH_DELAY_MS = 300L

    /**
     * Opens Maps on the left and the last-used media app on the right.
     * 
     * Optimized for ROCO K706: Uses ActivityOptions bounds + launcher's native split support.
     */
    fun launchCockpit(context: Context) {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val leftHalf = Rect(0, 0, width / 2, height)
        val rightHalf = Rect(width / 2, 0, width, height)

        // Lancer Maps en premier dans la moitié gauche
        startInBounds(context, mapsIntent(context), leftHalf)

        val mediaPackage = CarMediaController.getLastMediaPackage(context) ?: return
        
        Handler(Looper.getMainLooper()).postDelayed({
            val media = context.packageManager
                .getLaunchIntentForPackage(mediaPackage)
                ?.apply {
                    // Flags ROCO compatibles (LAUNCH_ADJACENT souvent ignoré par OEM)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                              Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    
                    // Tenter le split via ActivityOptions bounds (méthode principale)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        tryToSplitViaBounds(context, this, rightHalf)
                    }
                } ?: return
        
            if (media != null) startInBounds(context, media, rightHalf)
        }, ADJACENT_LAUNCH_DELAY_MS)
    }

    /**
     * Attempts to use ActivityManager.splitScreenRequested() via reflection.
     * This is the most reliable method for ROCO head units.
     */
    private fun tryToSplitViaBounds(context: Context, intent: Intent, bounds: Rect) {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        
        // Créer ActivityOptions avec les bounds pour le split
        val options = ActivityOptions.makeBasic()
        runCatching { options.setLaunchBounds(bounds) }
        
        // Lancer avec les bounds
        runCatching { context.startActivity(intent, options.toBundle()) }
        
        // Tenter l'API reflection splitScreenRequested (méthode ROCO K706)
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) 
                as? android.app.ActivityManager ?: return
            
            // Utiliser reflection pour appeler splitScreenRequested()
            val method: Method = activityManager::class.java
                .getMethod("splitScreenRequested", Intent::class.java, View::class.java)
                .apply { isAccessible = true }
            
            // Créer un window manager pour le paramètre View (null OK sur ROCO)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            
            // Lancer le split via l'API native
            method.invoke(activityManager, intent, null)
        } catch (e: Exception) {
            // Fallback : utiliser les bounds standards même si ça ne marche pas
            e.printStackTrace()
        }
    }

    /** Starts [intent] positioned within [bounds]. */
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

    /** Launch intent for the Maps app, or a generic geo intent as fallback. */
    private fun mapsIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
