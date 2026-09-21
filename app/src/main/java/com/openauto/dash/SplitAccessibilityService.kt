package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log
import android.widget.TextView
import kotlin.math.hypot

/**
 * A minimal [AccessibilityService] whose only job is to trigger the system's
 * built-in split-screen **the same way the manual recents gesture does** —
 * `performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)`.
 *
 * This head unit's ROM ignores the AOSP windowing APIs (`am task resize`,
 * `ActivityOptions.setLaunchWindowingMode`) that [SplitLauncher] falls back to,
 * but it still honours SystemUI's own split path — the one the user reaches by
 * long-pressing an app in recents and dragging it to a side. That path is
 * exactly what this global action drives, so routing through an accessibility
 * service is the closest programmatic match to the working manual gesture.
 *
 * The service holds no long-lived state and observes no events; it exists only
 * so the launcher can ask SystemUI to dock the foreground task on demand. The
 * user must enable it once under Settings → Accessibility (see
 * [SplitLauncher.openAccessibilitySettings]).
 */
class SplitAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "connected")
        updateOverlayForSplit()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hideSwapOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        hideSwapOverlay()
        super.onDestroy()
    }

    // Window changes are the only events we watch, and only to keep the floating
    // swap button visible exactly while the screen is split.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        updateOverlayForSplit()
    }

    override fun onInterrupt() {}

    /**
     * True when the display is currently showing two apps side by side. Detected
     * by counting visible application windows: a single full-screen app (or the
     * launcher home) has one, a split has two. Only window types/count are read,
     * never any window content.
     */
    private fun isInSplitMode(): Boolean = runCatching {
        windows?.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: 0
    }.getOrDefault(0) >= 2

    /** Show the floating swap button while split, hide it otherwise. */
    private fun updateOverlayForSplit() {
        if (isInSplitMode()) showSwapOverlay() else hideSwapOverlay()
    }

    private fun toggleSplitScreen(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }
            .onFailure { Log.e(TAG, "toggle split-screen failed", it) }
            .getOrDefault(false)

    /**
     * Swap the two split-screen panes by dispatching a **double-tap on the split
     * divider** — the AOSP gesture SystemUI maps to "swap". The divider sits on
     * the boundary between the two 50/50 panes, i.e. the centre of the display,
     * so we tap there. Gesture dispatch is global, so it can hit the divider even
     * though it lies outside our own pane.
     */
    private fun swapPanes(): Boolean {
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels / 2f
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS))
            .addStroke(GestureDescription.StrokeDescription(path, TAP_MS + GAP_MS, TAP_MS))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }
            .onFailure { Log.e(TAG, "swap gesture failed", it) }
            .getOrDefault(false)
    }

    // --- Global floating swap button ----------------------------------------
    //
    // A draggable button that floats above every app (not just the launcher) so
    // the split panes can be swapped from anywhere. It rides on a
    // TYPE_ACCESSIBILITY_OVERLAY window, which this already-enabled service may
    // add without the separate "draw over other apps" permission. Tap = swap;
    // long-press then drag = reposition (the spot is remembered).

    private var overlayView: View? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showSwapOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return

        val size = dp(56)
        val button = TextView(this).apply {
            text = "⇄"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(OVERLAY_COLOR)
                setStroke(dp(2), 0x66000000)
            }
        }

        val metrics = resources.displayMetrics
        val prefs = getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, metrics.widthPixels - size - dp(16))
            y = prefs.getInt(KEY_Y, (metrics.heightPixels - size) / 2)
        }

        button.setOnTouchListener(swapTouchListener(wm, button, params, prefs))

        runCatching { wm.addView(button, params) }
            .onSuccess { overlayView = button }
            .onFailure { Log.e(TAG, "add swap overlay failed", it) }
    }

    /** Tap vs. long-press-then-drag handling for the floating swap button. */
    @SuppressLint("ClickableViewAccessibility")
    private fun swapTouchListener(
        wm: WindowManager,
        button: View,
        params: WindowManager.LayoutParams,
        prefs: android.content.SharedPreferences
    ): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val handler = Handler(Looper.getMainLooper())

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false   // long-press held → in move mode
        var moved = false      // actually repositioned the button while dragging

        // Only a long-press puts the button into move mode. A plain tap — even one
        // that jitters a few pixels, which is normal on a car touchscreen — is not
        // treated as a drag, so it still swaps on release.
        val armDrag = Runnable {
            dragging = true
            button.animate().scaleX(1.15f).scaleY(1.15f).alpha(0.9f).setDuration(120).start()
        }

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragging = false; moved = false
                    handler.postDelayed(armDrag, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // A big move before the long-press fires cancels move mode, so
                    // it stays a tap (swap) rather than snapping into a drag.
                    if (!dragging && hypot(dx, dy) > slop * 3) {
                        handler.removeCallbacks(armDrag)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        if (hypot(dx, dy) > slop) moved = true
                        runCatching { wm.updateViewLayout(button, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(armDrag)
                    button.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                    if (moved) {
                        // A real drag: remember where it was moved to.
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        // Tap (or long-press without moving): swap the panes.
                        swapPanes()
                    }
                    dragging = false; moved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun hideSwapOverlay() {
        val view = overlayView ?: return
        overlayView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        }.onFailure { Log.e(TAG, "remove swap overlay failed", it) }
    }

    companion object {
        private const val TAG = "SplitA11yService"
        private const val TAP_MS = 40L
        private const val GAP_MS = 80L

        private const val OVERLAY_PREFS = "split_overlay_prefs"
        private const val KEY_X = "swap_x"
        private const val KEY_Y = "swap_y"
        private val OVERLAY_COLOR = 0xFF1A73E8.toInt()

        @Volatile
        private var instance: SplitAccessibilityService? = null

        /** True once the user has enabled the service and the system bound it. */
        val isConnected: Boolean get() = instance != null

        /**
         * Ask SystemUI to toggle split-screen. Returns false when the service
         * is not enabled/bound, so callers can fall back to another strategy.
         */
        fun requestSplit(): Boolean = instance?.toggleSplitScreen() ?: false

        /**
         * Swap the left/right (or top/bottom) split panes. Returns false when the
         * service is not enabled/bound.
         */
        fun swapSplit(): Boolean = instance?.swapPanes() ?: false
    }
}
