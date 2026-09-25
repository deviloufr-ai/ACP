package com.openauto.dash

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The media volume, changed the way this device listens to. Normally through
 * Android's own media volume. Many head units keep that volume fixed and drive
 * their amplifier from the MCU, which only follows the volume keys: there the
 * changes are made by pressing those keys through the privileged shell (root
 * or the internal ADB socket, see [DockShell]), exactly like the knob does.
 * Which way works is found out on the first change and kept for the run.
 */
object MediaVolume {
    private const val TAG = "MediaVolume"
    private const val STREAM = AudioManager.STREAM_MUSIC

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _byKeys = MutableStateFlow(false)
    /** Android's media volume has no effect here: the volume keys are pressed instead, and the level is unknown. */
    val byKeys: StateFlow<Boolean> = _byKeys.asStateFlow()

    private val _unavailable = MutableStateFlow(false)
    /** Neither way works: no effect from Android's volume and no shell to press the keys with. */
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    /** The level to come back to when unmuting a volume that was slid down to 0. */
    @Volatile private var lastAudible = 0

    fun audio(context: Context): AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun max(audio: AudioManager): Int = audio.getStreamMaxVolume(STREAM).coerceAtLeast(1)

    fun level(audio: AudioManager): Int = audio.getStreamVolume(STREAM)

    /** Muted, or slid down to nothing. */
    fun isMuted(audio: AudioManager): Boolean = audio.isStreamMute(STREAM) || level(audio) == 0

    /** Checked once the audio tile shows: a fixed volume can't be changed through Android at all. */
    fun check(audio: AudioManager) {
        if (audio.isVolumeFixed) _byKeys.value = true
    }

    /** Sets the level (the slider). Android's volume only: the keys can't reach a given level. */
    fun set(context: Context, level: Int) {
        val audio = audio(context)
        if (_byKeys.value) return
        if (level > 0) lastAudible = level
        val before = level(audio)
        runCatching { audio.setStreamVolume(STREAM, level, 0) }
        // Nothing moved at all: the ROM handles the volume itself. (A level merely
        // capped, e.g. by the safe-volume limit, still moved and doesn't count.)
        if (level != before && level(audio) == before && !audio.isStreamMute(STREAM)) useKeys()
    }

    fun raise(context: Context) = step(context, AudioManager.ADJUST_RAISE, KeyEvent.KEYCODE_VOLUME_UP)

    fun lower(context: Context) = step(context, AudioManager.ADJUST_LOWER, KeyEvent.KEYCODE_VOLUME_DOWN)

    fun toggleMute(context: Context) {
        val audio = audio(context)
        if (_byKeys.value) {
            press(context, KeyEvent.KEYCODE_VOLUME_MUTE)
            return
        }
        if (isMuted(audio)) {
            if (audio.isStreamMute(STREAM)) runCatching { audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0) }
            // Slid down to 0 rather than muted: unmuting alone would leave it silent.
            if (level(audio) == 0) {
                val back = lastAudible.takeIf { it > 0 } ?: (max(audio) / 3).coerceAtLeast(1)
                runCatching { audio.setStreamVolume(STREAM, back, 0) }
            }
            if (isMuted(audio)) useKeys(context, KeyEvent.KEYCODE_VOLUME_MUTE)
        } else {
            lastAudible = level(audio)
            runCatching { audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0) }
            if (!isMuted(audio)) useKeys(context, KeyEvent.KEYCODE_VOLUME_MUTE)
        }
    }

    private fun step(context: Context, direction: Int, keyCode: Int) {
        val audio = audio(context)
        if (_byKeys.value) {
            press(context, keyCode)
            return
        }
        val before = level(audio)
        val wasMuted = audio.isStreamMute(STREAM)
        // Already at the end of the range: nothing to change, nothing learnt.
        val atEnd = !wasMuted && if (direction == AudioManager.ADJUST_RAISE) before >= max(audio) else before <= 0
        runCatching { audio.adjustStreamVolume(STREAM, direction, 0) }
        val moved = level(audio) != before || audio.isStreamMute(STREAM) != wasMuted
        if (!moved && !atEnd) useKeys(context, keyCode)
    }

    /** From now on the keys are pressed; [keyCode] is the press the system just ignored. */
    private fun useKeys(context: Context? = null, keyCode: Int? = null) {
        if (!_byKeys.value) Log.i(TAG, "Android's media volume has no effect here: using the volume keys")
        _byKeys.value = true
        if (context != null && keyCode != null) press(context, keyCode)
    }

    private fun press(context: Context, keyCode: Int) {
        val app = context.applicationContext
        scope.launch {
            runCatching { DockShell.shell(app, "input keyevent $keyCode") }
                .onSuccess { _unavailable.value = false }
                .onFailure {
                    Log.w(TAG, "volume key $keyCode not pressed: ${it.message}")
                    _unavailable.value = true
                }
        }
    }
}
