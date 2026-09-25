package com.openauto.dash

import android.content.Context
import android.media.AudioManager
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How much louder the music gets with speed: one notch every [everyKmh] from [SpeedVolume.START_KMH], at most [maxNotches]. */
enum class SpeedVolumeLevel(@StringRes val titleRes: Int, @StringRes val hintRes: Int, val everyKmh: Int, val maxNotches: Int) {
    OFF(R.string.speed_volume_off, R.string.speed_volume_off_hint, 0, 0),
    LOW(R.string.speed_volume_low, R.string.speed_volume_low_hint, 40, 2),
    MEDIUM(R.string.speed_volume_medium, R.string.speed_volume_medium_hint, 25, 4),
    HIGH(R.string.speed_volume_high, R.string.speed_volume_high_hint, 15, 6)
}

/**
 * Speed-dependent volume: turns the music up as road noise grows and back
 * down as the car slows. It only ever adds and removes its own notches, one
 * a second, through [MediaVolume], so the driver's own volume changes are
 * kept and head units whose volume follows only the keys work too. Speed
 * comes from OBD when connected, GPS otherwise; without one it holds.
 */
object SpeedVolume {
    private const val PREFS = "speed_volume"
    private const val KEY = "level"

    /** Below this the car is in town: no boost. */
    const val START_KMH = 40
    /** A notch comes off only once the speed is this far under where it came on, so cruising at a threshold doesn't pump. */
    const val HYSTERESIS_KMH = 8
    private const val TICK_MS = 1_000L

    private val _level = MutableStateFlow(SpeedVolumeLevel.OFF)
    val level: StateFlow<SpeedVolumeLevel> = _level.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    /** Notches wanted for the current speed. */
    private var boost = 0
    /** Volume steps (or key presses) actually added and not yet given back. */
    private var applied = 0

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        _level.value = load(app)
        scope.launch {
            _level.map { it != SpeedVolumeLevel.OFF }.distinctUntilChanged().collectLatest { on ->
                if (on) follow(app)
            }
        }
    }

    fun save(context: Context, level: SpeedVolumeLevel) {
        _level.value = level
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, level.name).apply()
    }

    private fun load(context: Context): SpeedVolumeLevel {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return SpeedVolumeLevel.entries.firstOrNull { it.name == name } ?: SpeedVolumeLevel.OFF
    }

    /** Runs while the feature is on; switched off, it gives back what it added. */
    private suspend fun follow(app: Context) {
        var gps = false
        try {
            while (true) {
                if (!gps) gps = LocationFeed.acquire(app)
                tick(app)
                delay(TICK_MS)
            }
        } finally {
            if (gps) LocationFeed.release()
            giveBack(app)
        }
    }

    private fun tick(app: Context) {
        if (DemoMode.isOn || MediaVolume.unavailable.value) return
        val audio = MediaVolume.audio(app)
        // In a call the keys would change the call's volume; muted, a raise would unmute.
        if (inCall(audio)) return
        val keys = MediaVolume.byKeys.value
        if (!keys && MediaVolume.isMuted(audio)) return
        boost = target(speedKmh(), boost, _level.value)
        val want = boost * if (keys) 1 else unitFor(MediaVolume.max(audio))
        when {
            applied < want -> applied += nudge(app, up = true)
            applied > want -> applied -= nudge(app, up = false)
        }
    }

    /** One step up or down; returns how many steps it really moved. */
    private fun nudge(app: Context, up: Boolean): Int {
        val audio = MediaVolume.audio(app)
        val before = MediaVolume.level(audio)
        if (up) MediaVolume.raise(app) else MediaVolume.lower(app)
        // Pressed as a key (all along, or just found to be needed): the level can't be read, count the press.
        if (MediaVolume.byKeys.value) return 1
        val moved = MediaVolume.level(audio) - before
        return if (up) moved.coerceAtLeast(0) else (-moved).coerceAtLeast(0)
    }

    private fun giveBack(app: Context) {
        var tries = applied
        while (applied > 0 && tries-- > 0) {
            val moved = nudge(app, up = false)
            if (moved == 0) break
            applied -= moved
        }
        applied = 0
        boost = 0
    }

    private fun speedKmh(): Int? =
        if (ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED) ObdBluetoothManager.data.value.speedKmh
        else LocationFeed.freshSpeedKmh.value

    private fun inCall(audio: AudioManager): Boolean =
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

    /** Notches for [speedKmh] at [level], ignoring hysteresis. */
    internal fun boostFor(speedKmh: Int, level: SpeedVolumeLevel): Int {
        if (level == SpeedVolumeLevel.OFF || speedKmh < START_KMH) return 0
        return ((speedKmh - START_KMH) / level.everyKmh + 1).coerceAtMost(level.maxNotches)
    }

    /**
     * The notches to aim for from [current]: up as soon as the speed calls for
     * more, down only once it is [HYSTERESIS_KMH] under; an unknown speed holds.
     */
    internal fun target(speedKmh: Int?, current: Int, level: SpeedVolumeLevel): Int {
        val held = current.coerceIn(0, level.maxNotches)
        if (speedKmh == null) return held
        val raw = boostFor(speedKmh, level)
        if (raw > held) return raw
        val down = boostFor(speedKmh + HYSTERESIS_KMH, level)
        return if (down < held) down else held
    }

    /** Volume steps per notch: a notch is about 1/15 of the range, however fine the ROM's steps are. */
    internal fun unitFor(maxVolume: Int): Int = (maxVolume / 15f).roundToInt().coerceAtLeast(1)
}
