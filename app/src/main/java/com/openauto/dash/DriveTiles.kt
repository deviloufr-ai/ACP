@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Driving widgets: digital speed HUD, compass, trip computer, G-force meter
 * and the parking-spot finder. All fed by LiveFeeds (GPS, accelerometer).
 */

/** Small uppercase tile title with an optional trailing element. */
@Composable
internal fun TileHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            title,
            color = DashColors.Accent,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            style = MaterialTheme.typography.labelMedium
        )
        trailing()
    }
}

/** Keeps the GPS feed alive while this composable is on screen. */
@Composable
internal fun UseLocationFeed() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        LocationFeed.acquire(context)
        onDispose { LocationFeed.release() }
    }
}

/** Big glowing numerals shared by the speed HUD and clock. */
@Composable
internal fun HeroNumber(text: String, size: Int, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val glow = DashColors.Glow
    val accent = DashColors.Accent
    val lit = glow > 0f && !dimmed
    Text(
        text = text,
        modifier = modifier,
        color = if (lit) Color.Unspecified else if (dimmed) DashColors.Muted else DashColors.TextPrimary,
        fontSize = size.sp,
        lineHeight = size.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.06).em,
        maxLines = 1,
        style = TextStyle(
            brush = if (lit) Brush.verticalGradient(listOf(Color.White, lerp(Color.White, accent, 0.45f))) else null,
            shadow = if (lit) Shadow(accent.copy(alpha = 0.8f * glow), blurRadius = size * 0.6f) else null
        )
    )
}

// --- Speed HUD ------------------------------------------------------------------

/** Just the speed, as large as the tile allows. OBD when connected, GPS otherwise. */
@Composable
internal fun SpeedHudCard(obdData: ObdData, obdConnected: Boolean, modifier: Modifier = Modifier) {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val gpsFresh = location?.let { System.currentTimeMillis() - it.time < 5_000L } == true
    val speed = when {
        obdConnected -> obdData.speedKmh
        gpsFresh -> ((location?.speed ?: 0f) * 3.6f).roundToInt()
        else -> null
    }
    val source = when {
        obdConnected -> "OBD"
        gpsFresh -> "GPS"
        else -> "No signal"
    }
    val over = (speed ?: 0) >= SPEED_WARNING_KMH

    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            val numSize = (min(maxWidth.value * 0.42f, maxHeight.value * 0.62f)).coerceIn(40f, 150f).roundToInt()
            Column(modifier = Modifier.fillMaxSize()) {
                TileHeader("SPEED") {
                    Text(
                        source,
                        color = if (speed != null) DashColors.Good else DashColors.Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (over) {
                            Text(
                                text = speed.toString(),
                                color = DashColors.Warning,
                                fontSize = numSize.sp,
                                lineHeight = numSize.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.06).em,
                                maxLines = 1,
                                style = TextStyle(shadow = Shadow(DashColors.Warning.copy(alpha = 0.7f * DashColors.Glow), blurRadius = numSize * 0.5f))
                            )
                        } else {
                            HeroNumber(text = speed?.toString() ?: "--", size = numSize, dimmed = speed == null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "KM/H",
                            color = DashColors.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.25.em,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = (numSize * 0.16f).dp)
                        )
                    }
                }
            }
        }
    }
}

// --- Compass --------------------------------------------------------------------

/** Heading dial from GPS bearing, with altitude and GPS speed underneath. */
@Composable
internal fun CompassCard(modifier: Modifier = Modifier) {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val heading by LocationFeed.headingDeg.collectAsState()
    val textMeasurer = rememberTextMeasurer()
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val muted = DashColors.TextSecondary
    val glow = DashColors.Glow
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = muted)
    val northStyle = labelStyle.copy(color = DashColors.Warning)

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            TileHeader("COMPASS") {
                Text(
                    heading?.let { "${it.roundToInt()}° ${cardinal(it)}" } ?: "Move to get a heading",
                    color = if (heading != null) DashColors.TextPrimary else DashColors.Muted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f - 6.dp.toPx()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    // Dial rotates so the current heading sits at the top.
                    rotate(degrees = -(heading ?: 0f), pivot = c) {
                        drawCircle(color = Color.White.copy(alpha = 0.06f), radius = r, center = c)
                        for (i in 0 until 72) {
                            val major = i % 18 == 0
                            val mid = i % 6 == 0
                            val a = Math.toRadians((i * 5 - 90).toDouble())
                            val inner = r - if (major) 14.dp.toPx() else if (mid) 9.dp.toPx() else 5.dp.toPx()
                            drawLine(
                                color = if (major) accent else Color.White.copy(alpha = if (mid) 0.45f else 0.18f),
                                start = Offset(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner),
                                end = Offset(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r),
                                strokeWidth = if (major) 3f else 1.5f,
                                cap = StrokeCap.Round
                            )
                        }
                        listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).forEach { (l, deg) ->
                            val a = Math.toRadians((deg - 90).toDouble())
                            val lr = r - 26.dp.toPx()
                            val layout = textMeasurer.measure(l, if (l == "N") northStyle else labelStyle)
                            drawText(
                                layout,
                                topLeft = Offset(
                                    c.x + cos(a).toFloat() * lr - layout.size.width / 2f,
                                    c.y + sin(a).toFloat() * lr - layout.size.height / 2f
                                )
                            )
                        }
                    }
                    // Fixed lubber line + glowing heading marker at the top.
                    val tip = Offset(c.x, c.y - r)
                    if (glow > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.6f * glow), Color.Transparent), center = tip, radius = 16.dp.toPx()),
                            radius = 16.dp.toPx(), center = tip
                        )
                    }
                    drawLine(
                        brush = Brush.verticalGradient(listOf(accent, accent2), startY = tip.y, endY = c.y),
                        start = tip, end = Offset(c.x, c.y - r * 0.55f), strokeWidth = 4f, cap = StrokeCap.Round
                    )
                    drawCircle(color = Color.White, radius = 4.dp.toPx(), center = tip)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatBlock("Altitude", location?.takeIf { it.hasAltitude() }?.let { "${it.altitude.roundToInt()} m" } ?: "--")
                StatBlock("GPS speed", location?.let { "${(it.speed * 3.6f).roundToInt()} km/h" } ?: "--")
                StatBlock("Accuracy", location?.let { "±${it.accuracy.roundToInt()} m" } ?: "--")
            }
        }
    }
}

private fun cardinal(deg: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[(((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
}

@Composable
internal fun StatBlock(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = DashColors.TextPrimary) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(label, color = DashColors.Muted, letterSpacing = 1.sp, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

// --- Trip computer --------------------------------------------------------------

/** Distance, time, average and top speed since the last reset. */
@Composable
internal fun TripCard(modifier: Modifier = Modifier) {
    UseLocationFeed()
    val trip by LocationFeed.trip.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val km = trip.distanceM / 1000.0

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            TileHeader("TRIP") {
                TextButton(onClick = { LocationFeed.resetTrip() }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Reset", color = DashColors.Accent, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1.2f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        HeroNumber(text = if (km < 100) String.format(Locale.getDefault(), "%.1f", km) else km.roundToInt().toString(), size = 48)
                        Spacer(Modifier.width(6.dp))
                        Text("KM", color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.em,
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Text("since ${formatClock(trip.startedAt)}", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TripRow("Time", formatDuration(trip.elapsedMs))
                    TripRow("Moving", formatDuration(trip.movingMs))
                    TripRow("Average", "${trip.avgSpeedKmh.roundToInt()} km/h")
                    TripRow("Top", "${trip.maxSpeedKmh.roundToInt()} km/h")
                }
            }
        }
    }
}

@Composable
private fun TripRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        Text(value, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}

internal fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${String.format(Locale.US, "%02d", m)}m" else "${m}m ${String.format(Locale.US, "%02d", s % 60)}s"
}

internal fun formatClock(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(epochMs))

// --- G-force --------------------------------------------------------------------

/** Friction-circle style meter: a dot for the current lateral / longitudinal g. */
@Composable
internal fun GForceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        GForceFeed.acquire(context)
        onDispose { GForceFeed.release() }
    }
    val g by GForceFeed.g.collectAsState()
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val warning = DashColors.Warning
    val glow = DashColors.Glow

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            TileHeader("G-FORCE") {
                TextButton(onClick = { GForceFeed.resetPeaks() }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Reset peaks", color = DashColors.Accent, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val r = size.minDimension / 2f - 4.dp.toPx()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val scale = r / 1.2f   // 1.2 g at the rim
                    listOf(0.4f, 0.8f, 1.2f).forEach { ring ->
                        drawCircle(
                            color = if (ring >= 1.2f) warning.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.14f),
                            radius = ring * scale, center = c, style = Stroke(width = 1.5f)
                        )
                    }
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
                    val px = c.x + g.lateral.coerceIn(-1.2f, 1.2f) * scale
                    val py = c.y - g.longitudinal.coerceIn(-1.2f, 1.2f) * scale
                    val p = Offset(px, py)
                    val mag = min(1f, (abs(g.lateral) + abs(g.longitudinal)) / 1.2f)
                    val dotColor = lerp(accent, warning, mag)
                    drawLine(brush = Brush.linearGradient(listOf(accent2.copy(alpha = 0.2f), dotColor), start = c, end = p), start = c, end = p, strokeWidth = 3f, cap = StrokeCap.Round)
                    if (glow > 0f) {
                        drawCircle(brush = Brush.radialGradient(listOf(dotColor.copy(alpha = 0.55f * glow), Color.Transparent), center = p, radius = 18.dp.toPx()), radius = 18.dp.toPx(), center = p)
                    }
                    drawCircle(color = dotColor, radius = 7.dp.toPx(), center = p)
                    drawCircle(color = Color.White, radius = 3.dp.toPx(), center = p)
                }
                Spacer(Modifier.width(10.dp))
                BoxWithConstraints(modifier = Modifier.weight(0.9f).fillMaxHeight()) {
                    // Two-row tiles only have room for the live values; peaks need a taller tile.
                    val showPeaks = maxHeight >= 150.dp
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                        StatBlock("Lateral", String.format(Locale.US, "%+.2f g", g.lateral), Modifier.fillMaxWidth())
                        StatBlock("Accel / brake", String.format(Locale.US, "%+.2f g", g.longitudinal), Modifier.fillMaxWidth())
                        if (showPeaks) {
                            StatBlock("Peaks", String.format(Locale.US, "%.2f / %.2f g", g.peakLateral, g.peakLongitudinal), Modifier.fillMaxWidth(), valueColor = DashColors.Warning)
                        }
                    }
                }
            }
        }
    }
}

// --- Parking --------------------------------------------------------------------

/** Save where the car is parked; later shows distance and direction back to it. */
@Composable
internal fun ParkingCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    UseLocationFeed()
    LaunchedEffect(Unit) { ParkingStore.load(context) }
    val spot by ParkingStore.spot.collectAsState()
    val location by LocationFeed.location.collectAsState()
    val hasFix = location != null
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(spot) {
        while (spot != null) {
            tick = System.currentTimeMillis()
            delay(30_000)
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            TileHeader("PARKING") {
                if (spot != null) {
                    TextButton(onClick = { ParkingStore.clear(context) }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Clear", color = DashColors.Muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val s = spot
            if (s == null) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.LocalParking, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (hasFix) "Remember where the car is" else "Waiting for GPS…",
                        color = DashColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { location?.let { ParkingStore.save(context, it) } },
                        enabled = hasFix,
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text("Save parking spot") }
                }
            } else {
                val results = FloatArray(2)
                val here = location
                if (here != null) Location.distanceBetween(here.latitude, here.longitude, s.lat, s.lng, results)
                val dist = if (here != null) results[0] else null
                val bearing = if (here != null) results[1] else null
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(DashColors.AccentBrush)
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // Arrow points from the car's heading towards the spot.
                        val heading = LocationFeed.headingDeg.collectAsState().value ?: 0f
                        Icon(
                            Icons.Filled.Navigation, contentDescription = null, tint = DashColors.OnAccent,
                            modifier = Modifier.size(34.dp).rotate(((bearing ?: 0f) - heading + 360f) % 360f)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            dist?.let { formatDistance(it) } ?: "Distance unknown",
                            color = DashColors.TextPrimary, fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "Parked ${formatAgo(s.savedAt)} · ${bearing?.let { cardinal(it) } ?: ""}",
                            color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Button(
                        onClick = { walkTo(context, s) },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text("Walk there") }
                }
            }
        }
    }
}

private fun walkTo(context: Context, spot: ParkingSpot) {
    val uri = Uri.parse(String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=w", spot.lat, spot.lng))
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        val geo = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Parked car)", spot.lat, spot.lng, spot.lat, spot.lng))
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

internal fun formatDistance(m: Float): String =
    if (m < 1000f) "${m.roundToInt()} m" else String.format(Locale.getDefault(), "%.1f km", m / 1000f)

internal fun formatAgo(epochMs: Long): String {
    val mins = ((System.currentTimeMillis() - epochMs) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h ${mins % 60}m ago"
        else -> "${mins / (24 * 60)}d ago"
    }
}

