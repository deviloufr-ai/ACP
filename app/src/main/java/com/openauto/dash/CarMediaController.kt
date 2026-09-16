package com.openauto.dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of the currently active system media session. */
data class MediaState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false
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

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish(activeController)
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish(activeController)
        override fun onSessionDestroyed() {
            activeController = null
            _mediaState.value = MediaState()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> bind(controllers) }

    /** Starts observing active media sessions. Safe to call before access is granted. */
    fun start() {
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent)
            bind(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            // Notification access not granted yet; UI prompts the user to enable it.
            _mediaState.value = MediaState()
        }
    }

    /** Stops observing and releases callbacks. */
    fun stop() {
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
    }

    private fun bind(controllers: List<MediaController>?) {
        activeController?.unregisterCallback(controllerCallback)
        activeController = controllers?.firstOrNull()
        activeController?.registerCallback(controllerCallback)
        publish(activeController)
    }

    private fun publish(controller: MediaController?) {
        if (controller == null) {
            _mediaState.value = MediaState()
            return
        }
        val metadata = controller.metadata
        val playback = controller.playbackState
        _mediaState.value = MediaState(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            hasMedia = metadata != null
        )
    }

    fun playPause() {
        val controls = activeController?.transportControls ?: return
        if (_mediaState.value.isPlaying) controls.pause() else controls.play()
    }

    fun next() {
        activeController?.transportControls?.skipToNext()
    }

    fun previous() {
        activeController?.transportControls?.skipToPrevious()
    }

    companion object {
        /** True once the user has granted Notification access to this app. */
        fun hasNotificationAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabled.split(":").any { it.contains(context.packageName) }
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
