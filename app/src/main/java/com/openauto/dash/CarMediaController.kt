package com.openauto.dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of the currently active system media session. */
data class MediaState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    /** Track length in ms, or 0 when unknown (hides the progress bar). */
    val durationMs: Long = 0L,
    /** Album art / thumbnail from the session, or null when none is published. */
    val artwork: Bitmap? = null
)

/**
 * Bridges Android's [MediaSessionManager] into a Compose-friendly [StateFlow].
 *
 * Reads the active media session from other apps (title, artist, playback
 * state) and exposes transport controls. This requires Notification access,
 * which the user grants once via system settings — see
 * [hasNotificationAccess] / [openNotificationAccessSettings]. The paired
 * [MediaNotificationListenerService] is the component that access is granted to.
 */
class CarMediaController(private val context: Context) {

    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listenerComponent =
        ComponentName(context, MediaNotificationListenerService::class.java)

    private val _mediaState = MutableStateFlow(MediaState())
    val mediaState: StateFlow<MediaState> = _mediaState.asStateFlow()

    private var activeController: MediaController? = null
    private var boundControllers: List<MediaController> = emptyList()
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        // The callback is registered on every session, so re-pick the active one
        // whenever any of them changes state (e.g. YouTube Music starts playing).
        override fun onPlaybackStateChanged(state: PlaybackState?) = selectActive()
        override fun onMetadataChanged(metadata: MediaMetadata?) = selectActive()
        override fun onSessionDestroyed() = selectActive()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> bind(controllers) }

    /**
     * Starts observing active media sessions. Safe (and idempotent) to call
     * before access is granted — the UI re-invokes it once the user grants
     * Notification access so playback appears without an app restart.
     */
    fun start() {
        if (started) return
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent)
            bind(sessionManager.getActiveSessions(listenerComponent))
            started = true
        } catch (e: SecurityException) {
            // Notification access not granted yet; UI prompts the user to enable it.
            _mediaState.value = MediaState()
        }
    }

    /** Stops observing and releases callbacks. */
    fun stop() {
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        boundControllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        boundControllers = emptyList()
        activeController = null
        started = false
    }

    private fun bind(controllers: List<MediaController>?) {
        boundControllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        boundControllers = controllers.orEmpty()
        // Observe every session so we follow whichever one is actually playing.
        boundControllers.forEach { runCatching { it.registerCallback(controllerCallback) } }
        selectActive()
    }

    /** Picks the session that is currently playing, falling back to the first. */
    private fun selectActive() {
        activeController = boundControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: boundControllers.firstOrNull()
        publish(activeController)
    }

    private fun publish(controller: MediaController?) {
        if (controller == null) {
            _mediaState.value = MediaState()
            return
        }
        // Remember which app owns this session so the split-screen cockpit can
        // reopen the last-used media app.
        controller.packageName?.let { rememberLastMediaPackage(context, it) }
        val metadata = controller.metadata
        val playback = controller.playbackState
        _mediaState.value = MediaState(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            hasMedia = metadata != null,
            durationMs = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L),
            artwork = metadata?.artwork()
        )
    }

    /** First available artwork bitmap from the session metadata, if any. */
    private fun MediaMetadata.artwork(): Bitmap? =
        getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    /**
     * Current playback position in ms, extrapolated from the last reported
     * position so a progress bar advances smoothly while playing.
     */
    fun positionMs(): Long {
        if (DemoMode.isOn) return DemoMode.positionMs()
        val state = activeController?.playbackState ?: return 0L
        val base = state.position
        return if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            (base + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
        } else {
            base.coerceAtLeast(0L)
        }
    }

    fun playPause() {
        if (DemoMode.isOn) return DemoMode.playPause()
        val controls = activeController?.transportControls ?: return
        if (_mediaState.value.isPlaying) controls.pause() else controls.play()
    }

    fun next() {
        if (DemoMode.isOn) return DemoMode.next()
        activeController?.transportControls?.skipToNext()
    }

    fun previous() {
        if (DemoMode.isOn) return DemoMode.previous()
        activeController?.transportControls?.skipToPrevious()
    }

    companion object {
        private const val PREFS = "media_prefs"
        private const val KEY_LAST_MEDIA_PACKAGE = "last_media_package"

        /** Persists the package of the most recently active media app. */
        private fun rememberLastMediaPackage(context: Context, packageName: String) {
            if (packageName == context.packageName) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_MEDIA_PACKAGE, packageName)
                .apply()
        }

        /** The last media app seen playing, or null if none has been observed. */
        fun getLastMediaPackage(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_MEDIA_PACKAGE, null)

        /** True once the user has granted Notification access to this app. */
        fun hasNotificationAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            // Each entry is a flattened ComponentName ("pkg/cls"); match the
            // package exactly rather than as a loose substring.
            return enabled.split(":").any { entry ->
                entry.substringBefore("/") == context.packageName
            }
        }

        /** Opens the system screen where the user enables Notification access. */
        fun openNotificationAccessSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
