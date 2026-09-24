package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Live readings for the designed tiles. Each built-in widget turns the feeds
 * its standard tile uses into a [WidgetFace]. A null face means the widget is
 * in a state that needs its standard tile (connect the adapter, grant access,
 * save a spot...), so the design steps aside until the reading exists.
 */

/** The live face of [kind], or null while only the standard tile can help (see file comment). */
@Composable
internal fun rememberWidgetFace(kind: BuiltinKind, env: SkinTileEnv): WidgetFace? = when (kind) {
    BuiltinKind.TELEMETRY -> telemetryFace(env)
    BuiltinKind.SPEED_HUD -> speedFace(env)
    BuiltinKind.MEDIA -> mediaFace(env)
    BuiltinKind.NAVIGATION -> directionsFace(env)
    BuiltinKind.CLOCK -> clockFace()
    BuiltinKind.WEATHER -> weatherFace()
    BuiltinKind.RANGE -> rangeFace(env)
    BuiltinKind.OBD_ALL -> obdAllFace(env)
    BuiltinKind.OBD_DTC -> faultCodesFace(env)
    BuiltinKind.DOORS -> doorsFace()
    BuiltinKind.CAN_MON -> canMonitorFace()
    BuiltinKind.COMPASS -> compassFace()
    BuiltinKind.TRIP -> tripFace()
    BuiltinKind.GFORCE -> gForceFace()
    BuiltinKind.PARKING -> parkingFace()
    BuiltinKind.CALENDAR -> agendaFace()
    BuiltinKind.QUICK_DIAL -> quickDialFace()
    BuiltinKind.NOTIFICATIONS -> notificationsFace(env)
    BuiltinKind.AUDIO -> audioFace()
    BuiltinKind.FILTER_CARE -> filterFace()
    BuiltinKind.WARMUP -> warmupFace(env)
    BuiltinKind.BATTERY -> batteryFace(env)
    BuiltinKind.ECO_DRIVE -> ecoFace()
    BuiltinKind.FUEL_TO_DEST -> fuelToDestFace()
    BuiltinKind.BREAK_TIMER -> breakFace()
    // Live views and the spec sheet: they keep their content and get the design's frame (DesignFrame).
    BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.CAR3D, BuiltinKind.MY_CAR -> null
}

/**
 * Widgets framed rather than redrawn: live views (map, docked window, 3D
 * model) and the car's spec sheet, whose tap opens the car settings.
 */
internal val FRAMED_KINDS = setOf(BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.CAR3D, BuiltinKind.MY_CAR)

private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.getDefault(), pattern, *args)

// --- Vehicle -----------------------------------------------------------------------

@Composable
private fun telemetryFace(env: SkinTileEnv): WidgetFace? {
    if (env.obdConnection != ObdConnectionState.CONNECTED) return null
    val d = env.obdData
    return WidgetFace(
        icon = Icons.Filled.Speed,
        title = BuiltinKind.TELEMETRY.label,
        value = d.speedKmh.toString(), unit = "km/h",
        caption = "${d.rpm} rpm",
        fraction = d.speedKmh / 220f,
        alert = d.speedKmh >= SPEED_WARNING_KMH || d.coolantTempC >= 110,
        stats = listOf(
            FaceStat(stringResource(R.string.vehicle_rpm), d.rpm.toString()),
            FaceStat(stringResource(R.string.vehicle_coolant), "${d.coolantTempC} °C"),
            FaceStat(stringResource(R.string.vehicle_battery), fmt("%.1f V", d.voltage)),
            FaceStat(stringResource(R.string.vehicle_load), "${d.engineLoadPct} %")
        )
    )
}

@Composable
private fun obdAllFace(env: SkinTileEnv): WidgetFace? {
    if (env.obdConnection != ObdConnectionState.CONNECTED) return null
    val d = env.obdData
    return WidgetFace(
        icon = Icons.Filled.Sensors,
        title = BuiltinKind.OBD_ALL.label,
        value = d.rpm.toString(), unit = "rpm",
        caption = "${d.speedKmh} km/h",
        fraction = d.rpm / 7000f,
        stats = listOfNotNull(
            FaceStat(stringResource(R.string.vehicle_speed), "${d.speedKmh} km/h"),
            FaceStat(stringResource(R.string.vehicle_coolant), "${d.coolantTempC} °C"),
            FaceStat(stringResource(R.string.vehicle_intake_air), "${d.intakeTempC} °C"),
            FaceStat(stringResource(R.string.vehicle_throttle), "${d.throttlePct} %"),
            FaceStat(stringResource(R.string.vehicle_engine_load), "${d.engineLoadPct} %"),
            FaceStat(stringResource(R.string.vehicle_battery), fmt("%.1f V", d.voltage)),
            d.fuelLevelPct.takeIf { it > 0 }?.let { FaceStat(stringResource(R.string.vehicle_fuel_level), "$it %") }
        )
    )
}

@Composable
private fun rangeFace(env: SkinTileEnv): WidgetFace? {
    val fuel = rememberFuel(env.obdData, env.obdConnection) ?: return null
    return WidgetFace(
        icon = Icons.Filled.LocalGasStation,
        title = BuiltinKind.RANGE.label,
        value = fuel.rangeKm.toString(), unit = "km",
        caption = fmt("%d %% · %.1f L", fuel.percent, fuel.liters),
        fraction = fuel.percent / 100f,
        alert = fuel.percent <= 10,
        stats = listOf(
            FaceStat(stringResource(R.string.vehicle_fuel), "${fuel.percent} %"),
            FaceStat(stringResource(R.string.vehicle_in_tank), fmt("%.1f L", fuel.liters)),
            FaceStat(stringResource(R.string.vehicle_avg_use), fmt("%.1f L/100", fuel.avgUse))
        )
    )
}

@Composable
private fun faultCodesFace(env: SkinTileEnv): WidgetFace? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai by AiMechanic.state.collectAsState()
    val lamp by ObdBluetoothManager.lamp.collectAsState()
    val pending by ObdBluetoothManager.pending.collectAsState()
    var busy by remember { mutableStateOf(false) }
    // No scan yet: the standard tile explains the adapter and runs the first scan.
    val codes = ai.codes ?: return null
    val connected = env.obdConnection == ObdConnectionState.CONNECTED
    val titles = remember(codes, context) { codes.map { ObdCodes.describe(it).localizedTitle(context) } }
    val lampOn = lamp?.on == true
    return WidgetFace(
        icon = Icons.Filled.Warning,
        title = BuiltinKind.OBD_DTC.label,
        value = codes.size.toString(),
        unit = stringResource(R.string.design_codes_unit),
        caption = when {
            codes.isNotEmpty() -> "${codes.first()} · ${titles.first()}"
            lampOn -> stringResource(R.string.vehicle_lamp_on)
            else -> stringResource(R.string.ai_no_codes)
        },
        fraction = if (codes.isEmpty()) 0f else 1f,
        alert = codes.isNotEmpty() || lampOn,
        rows = codes.mapIndexed { i, c ->
            FaceRow("$c ${titles[i]}", if (c in pending) stringResource(R.string.vehicle_pending) else "", alert = true)
        },
        stats = listOfNotNull(lamp?.let { FaceStat(stringResource(R.string.design_engine_lamp), stringResource(if (it.on) R.string.design_on else R.string.design_off)) }),
        actions = listOf(
            FaceAction(Icons.Filled.Search, stringResource(R.string.vehicle_scan), primary = true, enabled = connected && !busy, onClick = {
                busy = true
                scope.launch {
                    ObdBluetoothManager.readTroubleCodes().onSuccess { AiMechanic.report(it, announce = false) }
                    busy = false
                }
            }),
            FaceAction(Icons.Filled.DeleteSweep, stringResource(R.string.vehicle_clear), enabled = connected && !busy && codes.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    ObdBluetoothManager.clearTroubleCodes().onSuccess { AiMechanic.cleared() }
                    busy = false
                }
            })
        )
    )
}

@Composable
private fun doorsFace(): WidgetFace? {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val doors by McuReader.doorState.collectAsState()
    val d = doors ?: return null
    val open = stringResource(R.string.vehicle_door_open_caps)
    val closed = stringResource(R.string.vehicle_door_closed)
    val all = listOf(
        stringResource(R.string.vehicle_door_front_left) to d.frontLeft,
        stringResource(R.string.vehicle_door_front_right) to d.frontRight,
        stringResource(R.string.vehicle_door_rear_left) to d.rearLeft,
        stringResource(R.string.vehicle_door_rear_right) to d.rearRight,
        stringResource(R.string.vehicle_door_tailgate) to d.tailgate,
        stringResource(R.string.vehicle_door_bonnet) to d.bonnet
    )
    val openCount = all.count { it.second }
    return WidgetFace(
        icon = Icons.Filled.SensorDoor,
        title = BuiltinKind.DOORS.label,
        value = openCount.toString(),
        unit = stringResource(R.string.design_doors_open_unit),
        caption = if (d.anyOpen) all.filter { it.second }.joinToString(" · ") { it.first } else stringResource(R.string.vehicle_doors_all_closed),
        fraction = openCount / all.size.toFloat(),
        alert = d.anyOpen,
        // Open doors first, so every design shows them before the closed ones.
        rows = all.sortedByDescending { it.second }.map { (name, isOpen) -> FaceRow(name, if (isOpen) open else closed, alert = isOpen) }
    )
}

@Composable
private fun canMonitorFace(): WidgetFace? {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val entries by McuReader.entries.collectAsState()
    val now by rememberWallClock(500L)
    if (entries.isEmpty()) return null
    val changed = entries.count { now - it.changedAt < 1_000L }
    return WidgetFace(
        icon = Icons.Filled.Sensors,
        title = BuiltinKind.CAN_MON.label,
        value = entries.size.toString(),
        unit = stringResource(R.string.design_can_ids),
        caption = stringResource(R.string.design_can_changed, changed),
        fraction = changed / entries.size.toFloat(),
        stats = listOf(FaceStat(stringResource(R.string.design_can_changed_label), changed.toString())),
        rows = entries.sortedByDescending { it.changedAt }.take(4).map {
            FaceRow("${it.key}  ${it.hex}", fmt("%.1f s", ((now - it.changedAt).coerceAtLeast(0L)) / 1000f))
        }
    )
}

// --- Driving -------------------------------------------------------------------------

@Composable
private fun speedFace(env: SkinTileEnv): WidgetFace {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val gpsFresh = location?.let { System.currentTimeMillis() - it.time < 5_000L } == true
    val speed = rememberSpeedKmh(env.obdData, env.obdConnection)
    val source = when {
        env.obdConnection == ObdConnectionState.CONNECTED -> "OBD"
        gpsFresh -> "GPS"
        else -> stringResource(R.string.info_speed_no_signal)
    }
    return WidgetFace(
        icon = Icons.Filled.Speed,
        title = BuiltinKind.SPEED_HUD.label,
        value = speed?.toString() ?: "--", unit = "km/h",
        caption = source,
        fraction = (speed ?: 0) / 200f,
        alert = (speed ?: 0) >= SPEED_WARNING_KMH
    )
}

@Composable
private fun compassFace(): WidgetFace {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val heading by LocationFeed.headingDeg.collectAsState()
    val h = heading
    return WidgetFace(
        icon = Icons.Filled.Explore,
        title = BuiltinKind.COMPASS.label,
        value = h?.let { stringResource(cardinalRes(it)) } ?: "--",
        unit = h?.let { "${it.roundToInt()}°" } ?: "",
        caption = if (h == null) stringResource(R.string.info_compass_no_heading) else "",
        fraction = h?.let { ((it % 360f) + 360f) % 360f / 360f },
        fullCircle = true,
        compass = true,
        stats = listOf(
            FaceStat(stringResource(R.string.info_compass_altitude), location?.takeIf { it.hasAltitude() }?.let { "${it.altitude.roundToInt()} m" } ?: "--"),
            FaceStat(stringResource(R.string.info_compass_gps_speed), location?.let { "${(it.speed * 3.6f).roundToInt()} km/h" } ?: "--"),
            FaceStat(stringResource(R.string.info_compass_accuracy), location?.let { "±${it.accuracy.roundToInt()} m" } ?: "--")
        )
    )
}

@Composable
private fun tripFace(): WidgetFace {
    UseLocationFeed()
    val trip by LocationFeed.trip.collectAsState()
    // Re-read each second so the elapsed time keeps moving while parked.
    rememberWallClock(1_000L).longValue
    val km = trip.distanceM / 1000.0
    return WidgetFace(
        icon = Icons.Filled.Timeline,
        title = BuiltinKind.TRIP.label,
        value = if (km < 100) fmt("%.1f", km) else km.roundToInt().toString(), unit = "km",
        caption = stringResource(R.string.info_trip_since, formatClock(trip.startedAt)),
        stats = listOf(
            FaceStat(stringResource(R.string.info_trip_time), formatDuration(trip.elapsedMs)),
            FaceStat(stringResource(R.string.info_trip_average), "${trip.avgSpeedKmh.roundToInt()} km/h"),
            FaceStat(stringResource(R.string.info_trip_top), "${trip.maxSpeedKmh.roundToInt()} km/h")
        ),
        actions = listOf(FaceAction(Icons.Filled.Refresh, stringResource(R.string.info_reset), onClick = { LocationFeed.resetTrip() }))
    )
}

@Composable
private fun gForceFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        GForceFeed.acquire(context)
        onDispose { GForceFeed.release() }
    }
    val g by GForceFeed.g.collectAsState()
    val total = sqrt(g.lateral * g.lateral + g.longitudinal * g.longitudinal)
    return WidgetFace(
        icon = Icons.Filled.Adjust,
        title = BuiltinKind.GFORCE.label,
        value = fmt("%.2f", total), unit = "g",
        caption = fmt("%+.2f / %+.2f g", g.lateral, g.longitudinal),
        fraction = total / 1.2f,
        alert = total >= 1f,
        stats = listOf(
            FaceStat(stringResource(R.string.info_gforce_lateral), fmt("%+.2f", g.lateral)),
            FaceStat(stringResource(R.string.info_gforce_accel_brake), fmt("%+.2f", g.longitudinal)),
            FaceStat(stringResource(R.string.info_gforce_peaks), fmt("%.2f / %.2f", g.peakLateral, g.peakLongitudinal))
        ),
        actions = listOf(FaceAction(Icons.Filled.Refresh, stringResource(R.string.info_gforce_reset_peaks), onClick = { GForceFeed.resetPeaks() }))
    )
}

@Composable
private fun parkingFace(): WidgetFace? {
    val context = LocalContext.current
    UseLocationFeed()
    LaunchedEffect(Unit) { ParkingStore.load(context) }
    val spot by ParkingStore.spot.collectAsState()
    val location by LocationFeed.location.collectAsState()
    rememberWallClock(30_000L).longValue
    // No spot saved: the standard tile has the Save button.
    val s = spot ?: return null
    val here = location
    val (dist, bearing) = remember(here, s) {
        if (here == null) null to null else {
            val results = FloatArray(2)
            Location.distanceBetween(here.latitude, here.longitude, s.lat, s.lng, results)
            results[0] to results[1]
        }
    }
    val text = dist?.let { formatDistance(it) } ?: "--"
    val ago = formatAgo(s.savedAt)
    return WidgetFace(
        icon = Icons.Filled.LocalParking,
        title = BuiltinKind.PARKING.label,
        value = text.substringBefore(' '), unit = text.substringAfter(' ', ""),
        caption = if (bearing != null) stringResource(R.string.info_parking_parked_dir, ago, stringResource(cardinalRes(bearing)))
        else stringResource(R.string.info_parking_parked, ago),
        // Closer is fuller: the last 2 km count down to the car.
        fraction = dist?.let { 1f - (it / 2000f).coerceIn(0f, 1f) },
        actions = listOf(
            FaceAction(Icons.Filled.DirectionsWalk, stringResource(R.string.info_parking_walk), primary = true, onClick = { walkTo(context, s) }),
            FaceAction(Icons.Filled.Close, stringResource(R.string.info_clear), onClick = { ParkingStore.clear(context) })
        )
    )
}

// --- Navigation and media ------------------------------------------------------------------

@Composable
private fun directionsFace(env: SkinTileEnv): WidgetFace? {
    val nav by NavDirections.state.collectAsState()
    if (!env.hasMediaAccess || !nav.active) return null
    val (value, unit) = nav.distanceParts
    val time = stringResource(R.string.design_nav_time)
    val distance = stringResource(R.string.design_nav_distance)
    val arrival = stringResource(R.string.design_nav_arrival)
    val art = remember(nav.icon) { nav.icon?.asImageBitmap() }
    return WidgetFace(
        icon = Icons.Filled.TurnRight,
        title = BuiltinKind.NAVIGATION.label,
        value = value.ifEmpty { "--" }, unit = unit,
        caption = nav.instruction,
        stats = nav.etaParts.map { part ->
            val label = when {
                Regex("""^\d{1,2}[:h.]\d{2}""").containsMatchIn(part) -> arrival
                NavState.DISTANCE.containsMatchIn(part) -> distance
                else -> time
            }
            FaceStat(label, part)
        },
        art = art,
        onClick = { openNavigationApp(env.context, nav) }
    )
}

@Composable
private fun mediaFace(env: SkinTileEnv): WidgetFace? {
    val state = env.mediaState
    if (!env.hasMediaAccess || !state.hasMedia) return null
    val positionMs = rememberMediaPosition(state, env.mediaController)
    val fraction = if (state.durationMs > 0L) (positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else null
    val art = remember(state.artwork) { state.artwork?.asImageBitmap() }
    val controller = env.mediaController
    return WidgetFace(
        icon = Icons.Filled.MusicNote,
        title = stringResource(R.string.info_now_playing),
        value = state.title.ifBlank { stringResource(R.string.info_nothing_playing) },
        textValue = true,
        caption = state.artist,
        fraction = fraction,
        art = art,
        stats = if (state.durationMs > 0L) listOf(
            FaceStat(stringResource(R.string.design_elapsed), formatTrackTime(positionMs)),
            FaceStat(stringResource(R.string.design_length), formatTrackTime(state.durationMs))
        ) else emptyList(),
        actions = listOf(
            FaceAction(Icons.Filled.SkipPrevious, stringResource(R.string.info_media_previous), onClick = { controller.previous() }),
            FaceAction(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(R.string.info_media_play_pause), primary = true, onClick = { controller.playPause() }
            ),
            FaceAction(Icons.Filled.SkipNext, stringResource(R.string.info_media_next), onClick = { controller.next() })
        )
    )
}

@Composable
private fun audioFace(): WidgetFace {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    // Follow the hardware knob and other apps, like the standard audio tile.
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }
    val muted = volume == 0
    fun step(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    return WidgetFace(
        icon = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
        title = BuiltinKind.AUDIO.label,
        value = (volume * 100f / max).roundToInt().toString(), unit = "%",
        caption = stringResource(R.string.design_media_volume),
        fraction = volume / max.toFloat(),
        alert = muted,
        actions = listOf(
            FaceAction(
                if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                stringResource(if (muted) R.string.info_audio_unmute else R.string.info_audio_mute),
                primary = true,
                onClick = { step(if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE) }
            ),
            FaceAction(Icons.Filled.Remove, stringResource(R.string.design_volume_down), onClick = { step(AudioManager.ADJUST_LOWER) }),
            FaceAction(Icons.Filled.Add, stringResource(R.string.design_volume_up), onClick = { step(AudioManager.ADJUST_RAISE) })
        )
    )
}

// --- Info ------------------------------------------------------------------------------------

@Composable
private fun clockFace(): WidgetFace {
    val context = LocalContext.current
    val now = rememberNow(1_000L)
    val locale = Locale.getDefault()
    val timeFmt = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    val dateFmt = remember(locale) { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale) }
    val cal = remember(now) { Calendar.getInstance().apply { time = now } }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    val s = cal.get(Calendar.SECOND)
    return WidgetFace(
        icon = Icons.Filled.Schedule,
        title = BuiltinKind.CLOCK.label,
        value = timeFmt.format(now),
        caption = dateFmt.format(now).replaceFirstChar { it.uppercase() },
        fraction = s / 60f,
        fullCircle = true,
        clock = Triple(h, m, s),
        onClick = { openClockApp(context) }
    )
}

@Composable
private fun weatherFace(): WidgetFace? {
    val w = rememberWeather() ?: return null
    return WidgetFace(
        icon = weatherIcon(w.code),
        title = BuiltinKind.WEATHER.label,
        value = w.tempC.roundToInt().toString(), unit = "°C",
        caption = w.condition,
        // -10 °C .. 40 °C across the gauge.
        fraction = ((w.tempC + 10.0) / 50.0).toFloat().coerceIn(0f, 1f),
        stats = listOfNotNull(
            FaceStat(stringResource(R.string.design_feels_like), "${w.feelsC.roundToInt()}°"),
            FaceStat(stringResource(R.string.design_wind), "${w.windKmh.roundToInt()} km/h"),
            if (!w.hiC.isNaN()) FaceStat(stringResource(R.string.design_low_high), "${w.loC.roundToInt()}° / ${w.hiC.roundToInt()}°") else null
        )
    )
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun agendaFace(): WidgetFace? {
    val context = LocalContext.current
    val allowed = granted(context, Manifest.permission.READ_CALENDAR)
    var events by remember { mutableStateOf<List<AgendaEvent>?>(null) }
    LaunchedEffect(allowed) {
        if (!allowed) return@LaunchedEffect
        while (true) {
            events = withContext(Dispatchers.IO) { loadAgenda(context) }
            delay(5 * 60_000)
        }
    }
    if (!allowed) return null
    val list = events ?: return null
    if (list.isEmpty()) return null
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    val noTitle = stringResource(R.string.info_agenda_no_title)
    val allDay = stringResource(R.string.info_agenda_all_day)
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    fun whenText(e: AgendaEvent): String = when {
        e.allDay -> allDay
        Calendar.getInstance().apply { timeInMillis = e.begin }.get(Calendar.DAY_OF_YEAR) == today -> timeFmt.format(Date(e.begin))
        else -> dayFmt.format(Date(e.begin))
    }
    val next = list.first()
    val now = System.currentTimeMillis()
    val span = (next.begin - now).coerceAtLeast(0L)
    return WidgetFace(
        icon = Icons.Filled.Event,
        title = BuiltinKind.CALENDAR.label,
        value = whenText(next), textValue = next.allDay || !whenText(next).first().isDigit(),
        caption = next.title.ifBlank { noTitle } + if (next.location.isNotBlank()) " · ${next.location}" else "",
        // The next event's approach over the coming 3 hours.
        fraction = 1f - (span / (3 * 3_600_000f)).coerceIn(0f, 1f),
        rows = list.map { e -> FaceRow(e.title.ifBlank { noTitle }, whenText(e), alert = e.begin <= now) },
        onClick = {
            context.launchSafely(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
        }
    )
}

@Composable
private fun quickDialFace(): WidgetFace? {
    val context = LocalContext.current
    val allowed = granted(context, Manifest.permission.READ_CONTACTS)
    var favourites by remember { mutableStateOf<List<Favourite>>(emptyList()) }
    LaunchedEffect(allowed) {
        if (allowed) favourites = withContext(Dispatchers.IO) { loadFavourites(context) }
    }
    if (!allowed || favourites.isEmpty()) return null
    fun dial(f: Favourite) {
        f.number?.let { context.launchSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) }
    }
    fun initials(name: String) = name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
    val first = favourites.first()
    return WidgetFace(
        icon = Icons.Filled.Call,
        title = BuiltinKind.QUICK_DIAL.label,
        value = first.name.substringBefore(' '), textValue = true,
        caption = first.number.orEmpty(),
        rows = favourites.map { f -> FaceRow(f.name, f.number.orEmpty(), badge = initials(f.name), onClick = { dial(f) }) },
        stats = favourites.drop(1).take(3).map { FaceStat(initials(it.name), it.name.substringBefore(' ')) },
        actions = listOf(
            FaceAction(Icons.Filled.Call, stringResource(R.string.design_call_first, first.name.substringBefore(' ')), primary = true,
                enabled = first.number != null, onClick = { dial(first) }),
            FaceAction(Icons.Filled.Dialpad, stringResource(R.string.info_quickdial_dialer), onClick = { context.launchSafely(Intent(Intent.ACTION_DIAL)) })
        )
    )
}

@Composable
private fun notificationsFace(env: SkinTileEnv): WidgetFace? {
    if (!env.hasMediaAccess) return null
    val items by NotificationFeed.items.collectAsState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val latest = items.firstOrNull()
    return WidgetFace(
        icon = Icons.Filled.Notifications,
        title = BuiltinKind.NOTIFICATIONS.label,
        value = items.size.toString(),
        unit = stringResource(R.string.design_notif_new_unit),
        caption = latest?.let { n -> listOf(n.title.ifEmpty { n.appLabel }, n.text).filter { it.isNotEmpty() }.joinToString(": ") }
            ?: stringResource(R.string.info_notif_empty),
        fraction = (items.size / 10f).coerceIn(0f, 1f),
        rows = items.map { n ->
            FaceRow(n.title.ifEmpty { n.appLabel }, timeFmt.format(Date(n.postedAt)), onClick = { runCatching { n.contentIntent?.send() } })
        },
        stats = latest?.let { listOf(FaceStat(stringResource(R.string.design_latest), timeFmt.format(Date(it.postedAt)))) }.orEmpty(),
        actions = if (items.isEmpty()) emptyList() else listOf(
            FaceAction(Icons.Filled.ClearAll, stringResource(R.string.info_clear), onClick = { NotificationFeed.dismissAll() })
        )
    )
}

/** Stand-in reading for the design picker while a widget has no live one (not connected, no access...). */
@Composable
internal fun sampleFace(kind: BuiltinKind): WidgetFace = WidgetFace(
    icon = kindIcon(kind),
    title = kind.label,
    value = when (kind) {
        BuiltinKind.CLOCK -> "12:30"
        BuiltinKind.MEDIA, BuiltinKind.QUICK_DIAL, BuiltinKind.CALENDAR -> kind.label
        else -> "88"
    },
    textValue = kind == BuiltinKind.MEDIA || kind == BuiltinKind.QUICK_DIAL || kind == BuiltinKind.CALENDAR,
    caption = kind.blurb,
    fraction = 0.6f,
    fullCircle = kind == BuiltinKind.COMPASS || kind == BuiltinKind.CLOCK,
    clock = if (kind == BuiltinKind.CLOCK) Triple(12, 30, 0) else null,
    compass = kind == BuiltinKind.COMPASS
)

// --- Car care (CarCareTiles.kt) -------------------------------------------------------

@Composable
private fun filterFace(): WidgetFace? {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    // No particle filter: the standard tile says so.
    if (!car.particleFilter) return null
    val streak = care.filter.shortStreak
    val drive = care.drive
    val last = care.filter.lastLongAt
    val now = System.currentTimeMillis()
    return WidgetFace(
        icon = kindIcon(BuiltinKind.FILTER_CARE),
        title = BuiltinKind.FILTER_CARE.label,
        value = streak.toString(),
        unit = stringResource(R.string.car_filter_short_unit),
        caption = stringResource(
            when {
                streak >= 6 -> R.string.car_filter_needs_drive_now
                streak >= CareRules.FILTER_WARN_STREAK -> R.string.car_filter_needs_drive
                else -> R.string.car_filter_ok
            }
        ),
        alert = streak >= CareRules.FILTER_WARN_STREAK,
        fraction = drive?.let { it.hotFastMs.toFloat() / CareRules.LONG_DRIVE_MS },
        rows = listOfNotNull(
            drive?.let {
                FaceRow(stringResource(R.string.car_filter_this_drive, (it.hotFastMs / 60_000).toInt(), (CareRules.LONG_DRIVE_MS / 60_000).toInt()), "")
            },
            FaceRow(
                if (last > 0) stringResource(R.string.car_filter_last_long, android.text.format.DateUtils.getRelativeTimeSpanString(last, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
                else stringResource(R.string.car_filter_last_long_never),
                ""
            )
        )
    )
}

@Composable
private fun warmupFace(env: SkinTileEnv): WidgetFace? {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    val now by rememberWallClock(1_000L)
    val t = env.obdData.coolantTempC
    if (env.obdConnection != ObdConnectionState.CONNECTED || t == 0) return null
    return WidgetFace(
        icon = kindIcon(BuiltinKind.WARMUP),
        title = BuiltinKind.WARMUP.label,
        value = t.toString(), unit = "°C",
        caption = when {
            t < car.coldC -> stringResource(R.string.car_warmup_cold, car.coldRpmLimit)
            t < car.hotC - 10 -> stringResource(R.string.car_warmup_warming)
            else -> stringResource(R.string.car_warmup_warm)
        },
        alert = t < car.coldC,
        fraction = t.toFloat() / car.hotC,
        rows = listOfNotNull(care.drive?.let { FaceRow(stringResource(R.string.car_running_for, formatDuration(now - it.startedAt)), "") })
    )
}

@Composable
private fun batteryFace(env: SkinTileEnv): WidgetFace? {
    val car by CarProfileStore.profile.collectAsState()
    val v = env.obdData.voltage
    if (env.obdConnection != ObdConnectionState.CONNECTED || v < LiveWatch.MIN_PLAUSIBLE_V || v > LiveWatch.MAX_PLAUSIBLE_V) return null
    val running = env.obdData.rpm > LiveWatch.RUNNING_RPM
    val (status, weak) = if (running) when {
        v >= LiveWatch.CHARGE_CLEAR_V -> R.string.car_battery_charging to false
        v >= LiveWatch.NOT_CHARGING_V -> R.string.car_battery_charging_low to true
        else -> R.string.car_battery_not_charging to true
    } else when {
        v >= 12.6 -> R.string.car_battery_full to false
        v >= LiveWatch.BATTERY_CLEAR_V -> R.string.car_battery_good to false
        v >= LiveWatch.WEAK_BATTERY_V -> R.string.car_battery_low to true
        else -> R.string.car_battery_weak to true
    }
    return WidgetFace(
        icon = kindIcon(BuiltinKind.BATTERY),
        title = BuiltinKind.BATTERY.label,
        value = fmt("%.1f", v), unit = "V",
        caption = stringResource(status),
        alert = weak,
        fraction = ((v - 11.5) / (14.8 - 11.5)).toFloat().coerceIn(0f, 1f),
        rows = listOf(
            FaceRow(
                car.batteryAh?.let { stringResource(R.string.car_battery_capacity, it) }
                    ?: stringResource(if (running) R.string.car_battery_hint_running else R.string.car_battery_hint_off),
                ""
            )
        )
    )
}

@Composable
private fun ecoFace(): WidgetFace? {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    // No drive yet: the standard tile explains what the score will be.
    val drive = care.drive ?: care.lastDrive ?: return null
    val score = drive.ecoScore
    val liters = drive.distanceKm * car.typicalUse / 100
    return WidgetFace(
        icon = kindIcon(BuiltinKind.ECO_DRIVE),
        title = stringResource(if (care.drive != null) R.string.car_eco_title else R.string.car_eco_title_last),
        value = score?.toString() ?: "--", unit = "/ 100",
        caption = stringResource(
            when {
                score == null -> R.string.car_eco_too_early
                score >= 80 -> R.string.car_eco_smooth
                score >= 60 -> R.string.car_eco_fair
                else -> R.string.car_eco_harsh
            }
        ),
        alert = score != null && score < 60,
        fraction = score?.let { it / 100f },
        stats = listOfNotNull(
            drive.sweetPercent?.let { FaceStat(stringResource(R.string.car_eco_band, car.sweetBand.first, car.sweetBand.last), "$it %") },
            FaceStat(stringResource(R.string.car_eco_hard), stringResource(R.string.car_eco_hard_value, drive.hardAccel, drive.hardBrake)),
            if (car.gearbox == GearboxType.ROBOTISED) FaceStat(stringResource(R.string.car_eco_clutch), drive.clutchHolds.toString()) else null,
            FaceStat(
                stringResource(R.string.car_eco_fuel, fmt("%.1f", drive.distanceKm)),
                stringResource(R.string.car_eco_fuel_value, fmt("%.1f", liters), fmt("%.2f", liters * car.fuelPrice), car.currency)
            )
        )
    )
}

@Composable
private fun fuelToDestFace(): WidgetFace? {
    val nav by NavDirections.state.collectAsState()
    val canFuel by McuReader.fuelPercent.collectAsState()
    val canRange by McuReader.rangeKm.collectAsState()
    val obd by ObdBluetoothManager.data.collectAsState()
    val car by CarProfileStore.profile.collectAsState()
    val range = remember(canFuel, canRange, obd.fuelLevelPct, car) { carFuelInfo(canFuel, obd.fuelLevelPct, canRange)?.rangeKm }
        ?: return null
    val toGo = if (nav.active) CareRules.remainingKm(nav.eta) else null
    val verdict = CareRules.fuelVerdict(range, toGo)
    val icon = kindIcon(BuiltinKind.FUEL_TO_DEST)
    val title = BuiltinKind.FUEL_TO_DEST.label
    if (verdict == null) {
        return WidgetFace(icon = icon, title = title, value = range.toString(), unit = "km", caption = stringResource(R.string.car_fuel_dest_no_nav))
    }
    val km = toGo ?: 0.0
    return WidgetFace(
        icon = icon, title = title,
        value = (range - km).toInt().toString(),
        unit = stringResource(R.string.car_fuel_dest_spare_unit),
        caption = stringResource(
            when (verdict) {
                FuelVerdict.ENOUGH -> R.string.car_fuel_dest_enough
                FuelVerdict.TIGHT -> R.string.car_fuel_dest_tight
                FuelVerdict.SHORT -> R.string.car_fuel_dest_short
            }
        ),
        alert = verdict != FuelVerdict.ENOUGH,
        fraction = (km / range).toFloat().coerceIn(0f, 1f),
        rows = listOf(FaceRow(stringResource(R.string.car_fuel_dest_detail, range, km.toInt()), ""))
    )
}

@Composable
private fun breakFace(): WidgetFace {
    val care by CarCare.state.collectAsState()
    val due = CareRules.FIRST_BREAK_MIN * 60_000L
    val driving = care.rest.drivingMs
    return WidgetFace(
        icon = kindIcon(BuiltinKind.BREAK_TIMER),
        title = BuiltinKind.BREAK_TIMER.label,
        value = formatDuration(driving),
        caption = stringResource(if (driving >= due) R.string.car_break_due else R.string.car_break_ok),
        alert = driving >= due,
        fraction = driving.toFloat() / due,
        rows = listOf(FaceRow(stringResource(R.string.car_break_hint), ""))
    )
}
