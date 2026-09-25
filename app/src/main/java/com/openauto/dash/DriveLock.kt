package com.openauto.dash

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * The drive lock: while the car moves, anything that needs more than a glance
 * (arranging tiles, settings, pickers, the app grid) waits until it has
 * stopped. Speed comes from OBD when connected, GPS otherwise.
 */

/** Whether the lock is on (Settings → Advanced). On by default: a launcher for the driver's seat. */
object DriveLockStore {
    private const val PREFS = "drive_lock"
    private const val KEY = "enabled"

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}

/** Moving from this speed up; a slow roll in a car park does not lock the launcher. */
internal const val MOVING_KMH = 8
/** Stopped from this speed down (held for [STOPPED_HOLD_MS]), so creeping in traffic does not flicker the lock. */
internal const val STOPPED_KMH = 3
internal const val STOPPED_HOLD_MS = 2_000L

private val NotMoving: State<Boolean> = mutableStateOf(false)

/**
 * True while the car is moving, when the lock is [enabled]. Never in demo
 * mode: its made-up speed must not lock the person exploring the launcher out.
 * With the lock off no speed is read, so the GPS stays off on a phone.
 *
 * The speed is followed in an effect rather than read in composition, so the
 * caller (the whole dashboard) only recomposes when the answer flips.
 */
@Composable
internal fun rememberMoving(enabled: Boolean, demo: Boolean): State<Boolean> {
    if (!enabled || demo) return NotMoving
    val moving = remember { mutableStateOf(false) }
    UseLocationFeed()
    LaunchedEffect(Unit) {
        combine(
            ObdBluetoothManager.connectionState,
            ObdBluetoothManager.data,
            LocationFeed.freshSpeedKmh
        ) { connection, obd, gps ->
            val speed = (if (connection == ObdConnectionState.CONNECTED) obd.speedKmh else gps) ?: 0
            when {
                speed >= MOVING_KMH -> true
                speed <= STOPPED_KMH -> false
                else -> null
            }
        }
            .distinctUntilChanged()
            .collectLatest { fast ->
                when (fast) {
                    true -> moving.value = true
                    false -> {
                        delay(STOPPED_HOLD_MS)
                        moving.value = false
                    }
                    null -> Unit
                }
            }
    }
    return moving
}
