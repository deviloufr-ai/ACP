package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "EmbedMaps"

// android.hardware.display.DisplayManager virtual-display flags (some @hide).
private const val FLAG_PUBLIC = 1 shl 0            // VIRTUAL_DISPLAY_FLAG_PUBLIC
private const val FLAG_PRESENTATION = 1 shl 1      // VIRTUAL_DISPLAY_FLAG_PRESENTATION
private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3  // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
private const val FLAG_TRUSTED = 1 shl 10          // VIRTUAL_DISPLAY_FLAG_TRUSTED (@hide, needs ADD_TRUSTED_DISPLAY)

/**
 * Experimental: render another app (default **Google Maps**) *inside* a dashboard
 * tile by launching it onto a [VirtualDisplay] we own and presenting that display's
 * [Surface] in a [SurfaceView] — the same mechanism car projection / Android Auto
 * use to show Maps on a secondary screen. Unlike the removed-in-12 hidden
 * `ActivityView`, `DisplayManager.createVirtualDisplay` + `setLaunchDisplayId` are
 * public APIs that work on Android 10–12.
 *
 * KNOWN LIMITS on this ROM (to be verified by this retry):
 *  - a previous `ActivityView` attempt rendered black; if this also goes black the
 *    ROM's SurfaceFlinger won't composite app content onto an app-owned display.
 *  - touch input is NOT forwarded yet: a plain VirtualDisplay has no input channel,
 *    so the embedded map is view-only until/unless we add trusted-display input
 *    plumbing. The tile shows a status line so a failure is legible, not just black.
 */
@Composable
fun EmbeddedMapsPanel(
    modifier: Modifier = Modifier,
    packageName: String = "com.google.android.apps.maps"
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Starting embedded display…") }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    val holder = EmbedController(ctx, packageName) { status = it }
                    getHolder().addCallback(holder)
                    // Best-effort touch forwarding (works only on a trusted display).
                    setOnTouchListener { _, ev -> holder.forwardTouch(ev); true }
                }
            }
        )
        // Status text sits behind the surface once it renders; visible while black.
        Text(
            text = status,
            color = Color(0xFF9AA0A6),
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Owns the [VirtualDisplay] for one [SurfaceView]: creates it when the surface is
 * ready, launches the target app onto it, and tears it down when the surface goes.
 */
private class EmbedController(
    private val context: Context,
    private val packageName: String,
    private val onStatus: (String) -> Unit
) : SurfaceHolder.Callback {

    private var virtualDisplay: VirtualDisplay? = null
    private var displayId: Int = -1

    override fun surfaceCreated(holder: SurfaceHolder) { /* wait for size in changed */ }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (virtualDisplay != null) {
            // Surface resized: just resize the existing display, keep the app.
            runCatching { virtualDisplay?.resize(width, height, densityDpi()) }
            return
        }
        createAndLaunch(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed → releasing virtual display $displayId")
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        displayId = -1
    }

    private fun densityDpi(): Int = context.resources.displayMetrics.densityDpi

    private fun createAndLaunch(surface: Surface, width: Int, height: Int) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val base = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY

        // Try a TRUSTED display first (lets activities launch + take input); fall
        // back to an untrusted public display if the permission isn't granted.
        val vd = createVirtualDisplay(dm, surface, width, height, base or FLAG_TRUSTED)
            ?: createVirtualDisplay(dm, surface, width, height, base)

        if (vd == null) {
            onStatus("Could not create embedded display (see logcat $TAG)")
            return
        }
        virtualDisplay = vd
        displayId = vd.display?.displayId ?: -1
        Log.d(TAG, "virtual display created id=$displayId ${width}x$height dpi=${densityDpi()}")

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        if (intent == null) {
            onStatus("$packageName is not installed")
            return
        }

        val options = ActivityOptions.makeBasic().apply {
            runCatching { launchDisplayId = displayId }
                .onFailure { Log.e(TAG, "setLaunchDisplayId failed", it) }
        }
        val result = runCatching { context.startActivity(intent, options.toBundle()) }
        if (result.isSuccess) {
            onStatus("Launched $packageName on display $displayId — if this stays black, the ROM won't composite it")
            Log.d(TAG, "startActivity ok on display $displayId")
        } else {
            val e = result.exceptionOrNull()
            onStatus("Launch blocked: ${e?.javaClass?.simpleName}: ${e?.message}")
            Log.e(TAG, "startActivity on display $displayId failed", e)
        }
    }

    private fun createVirtualDisplay(
        dm: DisplayManager,
        surface: Surface,
        width: Int,
        height: Int,
        flags: Int
    ): VirtualDisplay? = runCatching {
        dm.createVirtualDisplay("OpenAutoDashEmbed", width, height, densityDpi(), surface, flags)
    }.onFailure { Log.w(TAG, "createVirtualDisplay flags=0x${flags.toString(16)} failed: ${it.message}") }
        .getOrNull()

    /** Best-effort: retarget the event to our display and inject it (needs signature perms). */
    fun forwardTouch(ev: MotionEvent) {
        val id = displayId
        if (id < 0) return
        runCatching {
            val copy = MotionEvent.obtain(ev)
            MotionEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(copy, id)
            val im = context.getSystemService(Context.INPUT_SERVICE)
            im.javaClass
                .getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                .invoke(im, copy, 0 /* ASYNC */)
            copy.recycle()
        }.onFailure { Log.v(TAG, "touch inject unavailable: ${it.message}") }
    }
}
