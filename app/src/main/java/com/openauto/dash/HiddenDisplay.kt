package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display

/*
 * Where a parked window goes: a second, invisible display.
 *
 * A freeform window cannot be hidden on the screen. The window manager keeps
 * 48 × 32 dp of it in view however far it is pushed, this ROM draws floating
 * windows above everything, and closing the window would end a Maps guidance
 * or stop the music. What the window manager will do, through the same shell
 * the docking uses (`am display move-stack`), is move a whole stack onto
 * another display. So the launcher owns a private virtual display the size of
 * the screen that is composed into a surface nobody looks at, and a parked
 * window is moved there: the app keeps running and drawing exactly as before,
 * with nothing of it on the screen and nothing covering it. Docking the window
 * again moves the stack back onto the screen, size, place and state intact.
 *
 * The display matches the screen's size and density so an app moved across
 * sees no configuration change (Android also gives a virtual display the
 * screen's input configuration). It is private, so only the launcher can open
 * windows on it: an app cannot add a *new* window (a dialog) while parked
 * there, but its existing ones are simply carried across. It lives as long as
 * the process; with it gone, the windows on it are closed rather than dropped
 * fullscreen over the dashboard, and the tiles reopen their apps.
 */
internal object HiddenDisplay {

    private const val TAG = "HiddenDisplay"

    private const val NAME = "Dashwheel parked windows"

    /** Consecutive failed moves onto the display before it is given up on for this process. */
    private const val MAX_FAILURES = 3

    /**
     * `DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL`, not in
     * the SDK: when the display goes, the windows on it are closed. Without it
     * Android moves them onto the screen fullscreen, over the dashboard.
     */
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    @Volatile private var disabled = false
    private var failures = 0

    /** The display's id, or null while it does not exist (never created, or given up on). */
    val id: Int?
        get() = display?.display?.displayId

    /**
     * The display's id, creating it on first use; null when it cannot be made
     * or was given up on, in which case windows are parked in the corner instead.
     */
    // The destroy-on-removal flag is real but hidden from the SDK, so lint
    // doesn't know it belongs with the public ones it is combined with.
    @SuppressLint("WrongConstant")
    @Synchronized
    fun acquire(context: Context): Int? {
        display?.let { return it.display.displayId }
        if (disabled) return null
        val app = context.applicationContext
        val dm = app.getSystemService(DisplayManager::class.java) ?: return giveUp("no display manager")
        val screen = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return giveUp("no default display")
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            screen.getRealMetrics(it)
        }
        val worker = HandlerThread("hidden-display").apply { start() }
        // The surface the display is composed into. Frames are taken and dropped
        // as they come, so the compositor never waits on a full queue; PRIVATE
        // keeps them on the GPU, nothing is ever read back.
        val sink = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, ImageFormat.PRIVATE, 2)
        sink.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(worker.looper))
        val made = runCatching {
            dm.createVirtualDisplay(
                NAME, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, sink.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL
            )
        }.getOrElse {
            Log.w(TAG, "could not create the hidden display", it)
            null
        }
        if (made == null) {
            sink.close()
            worker.quitSafely()
            return giveUp("creation refused")
        }
        display = made
        reader = sink
        thread = worker
        failures = 0
        Log.i(TAG, "hidden display ${made.display.displayId}: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi} dpi")
        return made.display.displayId
    }

    /**
     * A move onto the display failed (the shell lacks the permission, the ROM
     * refuses...). After a few in a row the display is not worth keeping: from
     * then on windows are parked in the corner.
     */
    @Synchronized
    fun noteMoveFailed(e: Throwable) {
        failures++
        Log.w(TAG, "move onto the hidden display failed ($failures/$MAX_FAILURES)", e)
        if (failures >= MAX_FAILURES) release("moves keep failing")
    }

    @Synchronized
    fun noteMoveSucceeded() {
        failures = 0
    }

    /**
     * Lets the display go, closing every window still parked on it, and stops
     * using it for the rest of the process. The last resort for a window that
     * will not come back onto the screen: its tile reopens the app afresh.
     */
    @Synchronized
    fun release(reason: String) {
        val d = display ?: run { disabled = true; return }
        Log.w(TAG, "releasing the hidden display ($reason)")
        runCatching { d.release() }.onFailure { Log.w(TAG, "release failed", it) }
        runCatching { reader?.close() }
        thread?.quitSafely()
        display = null
        reader = null
        thread = null
        disabled = true
    }

    private fun giveUp(why: String): Int? {
        Log.w(TAG, "no hidden display: $why; windows are parked in the corner instead")
        disabled = true
        return null
    }
}
