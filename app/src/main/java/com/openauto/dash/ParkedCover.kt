package com.openauto.dash

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/*
 * The fallback when a window cannot go onto the [HiddenDisplay] (and for
 * picture-in-picture): it is pushed into the bottom-right corner instead, but
 * the window manager keeps 48 × 32 dp of any floating window on screen however
 * far it is pushed (WindowState's MINIMUM_VISIBLE_WIDTH / HEIGHT), so a corner
 * of Maps would still peek out, over the dashboard or over another app. This
 * black patch, like the screen's bezel, covers that corner while a window is
 * parked there. It takes the taps on it, so none reach the hidden window; the
 * launcher bar stops short of it meanwhile, so its ⋮ button stays usable.
 */

internal object ParkedCover {

    private const val TAG = "ParkedCover"

    /** What the window manager leaves visible, plus a margin for the window's shadow. */
    const val WIDTH_DP = 48 + 6
    private const val HEIGHT_DP = 32 + 6

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    private val _showing = MutableStateFlow(false)
    /** True while the cover is on screen: the launcher bar makes room for it. */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    /**
     * Covers the bottom-right corner of [context]'s screen (the corner windows
     * are parked in). [wanted] is asked again on the main thread, so a window
     * unparked meanwhile doesn't leave the cover behind.
     */
    fun show(context: Context, wanted: () -> Boolean) {
        val app = context.applicationContext
        val dm = context.resources.displayMetrics
        val w = (WIDTH_DP * dm.density).roundToInt()
        val h = (HEIGHT_DP * dm.density).roundToInt()
        val x = dm.widthPixels - w
        val y = dm.heightPixels - h
        main.post {
            if (!wanted()) return@post
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lp = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
                title = "Dashwheel parked window cover"
            }
            val existing = view
            if (existing != null) {
                runCatching { wm.updateViewLayout(existing, lp) }.onFailure { Log.w(TAG, "move failed", it) }
                return@post
            }
            val v = View(app).apply { setBackgroundColor(Color.BLACK) }
            runCatching { wm.addView(v, lp) }
                .onSuccess { view = v; _showing.value = true; Log.i(TAG, "covering the parked corner [$x,$y ${x + w},${y + h}]") }
                .onFailure { Log.w(TAG, "could not add the cover", it) }
        }
    }

    /** Removes the cover, unless [wanted] says a window was parked again meanwhile. */
    fun hide(context: Context, wanted: () -> Boolean) {
        val app = context.applicationContext
        main.post {
            if (wanted()) return@post
            val v = view ?: return@post
            view = null
            _showing.value = false
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeViewImmediate(v) }.onFailure { Log.w(TAG, "remove failed", it) }
        }
    }
}
