package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

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
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // No events are observed and there is nothing to interrupt; the service is
    // only ever driven imperatively via [requestSplit].
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun toggleSplitScreen(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }
            .onFailure { Log.e(TAG, "toggle split-screen failed", it) }
            .getOrDefault(false)

    companion object {
        private const val TAG = "SplitA11yService"

        @Volatile
        private var instance: SplitAccessibilityService? = null

        /** True once the user has enabled the service and the system bound it. */
        val isConnected: Boolean get() = instance != null

        /**
         * Ask SystemUI to toggle split-screen. Returns false when the service
         * is not enabled/bound, so callers can fall back to another strategy.
         */
        fun requestSplit(): Boolean = instance?.toggleSplitScreen() ?: false
    }
}
