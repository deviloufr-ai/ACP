package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.roundToInt

/*
 * Shared helpers for the whole-design skins: live values (speed, fuel, weather,
 * time, playback), system font families and small formatting bits, so each
 * skin file only has to draw.
 */

/** Condensed sans for dial numerals and engraved labels (Roboto Condensed on Android). */
internal val CondensedFamily: FontFamily =
    FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))

/** The current time, re-read on each [periodMs] boundary. */
@Composable
internal fun rememberNow(periodMs: Long = 1_000L): Date {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = Date()
            delay(periodMs - System.currentTimeMillis() % periodMs)
        }
    }
    return now
}

/**
 * Speed in km/h from OBD when connected, else from a GPS fix under 5 s old,
 * else null. Keeps the GPS feed running while on screen.
 */
@Composable
internal fun rememberSpeedKmh(obdData: ObdData, connection: ObdConnectionState): Int? {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val gpsFresh = location?.let { System.currentTimeMillis() - it.time < 5_000L } == true
    return when {
        connection == ObdConnectionState.CONNECTED -> obdData.speedKmh
        gpsFresh -> ((location?.speed ?: 0f) * 3.6f).roundToInt()
        else -> null
    }
}

/** Weather at the car, refreshed the way the standard weather tile does it; null until the first fetch. */
@Composable
internal fun rememberWeather(): Weather? {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val weather by WeatherRepo.weather.collectAsState()
    LaunchedEffect(location?.latitude?.let { (it * 20).roundToInt() }, location?.longitude?.let { (it * 20).roundToInt() }) {
        val l = location ?: return@LaunchedEffect
        while (true) {
            WeatherRepo.refresh(l.latitude, l.longitude)
            delay(60_000)
        }
    }
    return weather
}

/** Fuel level and the range it gives, and where the level came from ("CANbox" or "OBD"). */
internal class FuelInfo(val percent: Int, val rangeKm: Int, val source: String)

/**
 * Fuel from the CANbox when learned, else the OBD fuel PID, else null (the
 * standard [RangeCard] explains how to learn it). Keeps the CANbox stream
 * running while on screen.
 */
@Composable
internal fun rememberFuel(obdData: ObdData, connection: ObdConnectionState): FuelInfo? {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val canFuel by McuReader.fuelPercent.collectAsState()
    val obdFuel = if (connection == ObdConnectionState.CONNECTED) obdData.fuelLevelPct else 0
    val pct = canFuel ?: obdFuel.takeIf { it > 0 } ?: return null
    val liters = pct / 100.0 * TANK_LITERS
    return FuelInfo(pct, (liters / AVG_L_PER_100KM * 100).toInt(), if (canFuel != null) "CANbox" else "OBD")
}

/** Playback progress 0..1, and 0 while the duration is unknown. */
@Composable
internal fun rememberMediaFraction(mediaState: MediaState, controller: CarMediaController): Float {
    val positionMs = rememberMediaPosition(mediaState, controller)
    return if (mediaState.durationMs > 0L) (positionMs.toFloat() / mediaState.durationMs).coerceIn(0f, 1f) else 0f
}

/** "2:14" style track time. */
internal fun formatTrackTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Label under an app icon: the app's name, or the last part of its package while apps load. */
internal fun appLabel(app: AppEntry?, packageName: String): String = app?.label ?: packageName.substringAfterLast('.')

/** Opens the clock / alarms app, as tapping the standard clock tile does. */
internal fun openClockApp(context: Context) {
    runCatching {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Rotation in degrees that keeps turning while [running] and holds its angle
 * when paused (a record or reel that stops where it is). Read it in a draw or
 * graphicsLayer lambda so only drawing reruns each frame.
 */
@Composable
internal fun rememberSpin(periodMs: Int, running: Boolean = true): State<Float> {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(running, periodMs) {
        if (!running) return@LaunchedEffect
        while (true) {
            val start = angle.value % 360f
            angle.snapTo(start)
            angle.animateTo(start + 360f, tween(periodMs, easing = LinearEasing))
        }
    }
    return angle.asState()
}

/** 0→1 looping every [periodMs] (restarting, or reversing when [reverse]), for background motion. */
@Composable
internal fun rememberLoop(periodMs: Int, reverse: Boolean = false): State<Float> =
    rememberInfiniteTransition(label = "loop").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(periodMs, easing = LinearEasing),
            if (reverse) RepeatMode.Reverse else RepeatMode.Restart
        ),
        label = "loop"
    )
