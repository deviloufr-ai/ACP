package com.openauto.dash

import android.media.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Media Controller Manager - Interfaces with Android's MediaSessionManager.
 * Captures audio metadata and media events for the unified media panel.
 */
class CarMediaController {
    
    private val mediaSessionManager: MediaSessionManager = 
        getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    
    // Exposed state for Jetpack Compose UI
    private val _mediaState = MutableStateFlow(
        MediaState(
            title = "",
            artist = "",
            isPlaying = false,
            hasVideoContent = false
        )
    )
    
    val mediaState: StateFlow<MediaState> = _mediaState.asStateFlow()
    
    // Callback for media event interception
    var onPlayPauseListener: ((Boolean) -> Unit)? = null
    
    var onNextListener: ((Boolean) -> Unit)? = null
    
    var onPreviousListener: ((Boolean) -> Unit)? = null
    
    init {
        registerMediaSessionCallback()
    }
    
    private fun registerMediaSessionCallback() {
        mediaSessionManager.registerCallback(
            "com.openauto.dash",
            object : MediaSessionService.Callback {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        MediaPlayer.PLAYBACK_STATE_PLAYING -> _mediaState.value = _mediaState.value.copy(isPlaying = true)
                        MediaPlayer.PLAYBACK_STATE_PAUSED -> _mediaState.value = _mediaState.value.copy(isPlaying = false)
                        MediaPlayer.PLAYBACK_STATE_STOPPED -> _mediaState.value = _mediaState.value.copy(isPlaying = false)
                    }
                }
                
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (metadata != null) {
                        val title = metadata.title ?: ""
                        val artist = metadata.artist ?: ""
                        val hasVideoContent = metadata.hasVideoContent
                        
                        _mediaState.value = _mediaState.value.copy(
                            title = title,
                            artist = artist,
                            hasVideoContent = hasVideoContent
                        )
                    }
                }
                
                override fun onTransportControlsCommand(command: Int) {
                    // Intercept media commands
                    when (command) {
                        MediaPlayer.PLAYBACK_COMMAND_PLAY -> handlePlay()
                        MediaPlayer.PLAYBACK_COMMAND_PAUSE -> handlePause()
                        MediaPlayer.PLAYBACK_COMMAND_SKIP_TO_NEXT_TRACK -> handleNext()
                        MediaPlayer.PLAYBACK_COMMAND_SKIP_TO_PREVIOUS_TRACK -> handlePrevious()
                    }
                }
                
                override fun onPlaybackSpeedChanged(playbackSpeed: Float) {
                    // Handle playback speed changes if needed
                }
            },
            null
        )
    }
    
    private fun handlePlay() {
        _mediaState.value = _mediaState.value.copy(isPlaying = true)
        onPlayPauseListener?.invoke(true)
    }
    
    private fun handlePause() {
        _mediaState.value = _mediaState.value.copy(isPlaying = false)
        onPlayPauseListener?.invoke(false)
    }
    
    private fun handleNext() {
        onNextListener?.invoke(true)
    }
    
    private fun handlePrevious() {
        onPreviousListener?.invoke(true)
    }
    
    fun clearCallback() {
        mediaSessionManager.unregisterCallback("com.openauto.dash")
    }
}

/**
 * Data class representing current media state.
 */
data class MediaState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val hasVideoContent: Boolean = false
)
