@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.Manifest
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * OBD tiles: hero speed gauge, rev bar, meter chips, fault codes, all-data, fuel & range.
 */

internal const val SPEED_WARNING_KMH = 110

/** Battery bar: 11 V empty to 15 V full; healthy between 12 and 15 V. */
internal fun batteryFraction(voltage: Double): Float = ((voltage - 11.0) / 4.0).toFloat()
internal fun batteryColor(voltage: Double): Color =
    if (voltage in 12.0..15.0) DashColors.Good else DashColors.Warning

/** The "connect first" body shared by the OBD cards. */
@Composable
internal fun ObdNotConnected(onConnect: () -> Unit) {
    Text("OBD not connected", color = DashColors.Muted)
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onConnect,
        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.Background)
    ) { Text("Connect") }
}

@Composable
internal fun ObdCard(
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    onPickDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (DashColors.Original) {
        OriginalObdCard(obdData, connection, onConnect, onPickDevice, modifier)
        return
    }
    val connected = connection == ObdConnectionState.CONNECTED
    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            // Short tiles drop the secondary chips; tall tiles stack the RPM bar
            // and chips under the gauge instead of beside it.
            val compact = maxHeight < 250.dp
            val stacked = maxWidth < maxHeight * 1.15f

            Column(modifier = Modifier.fillMaxSize()) {
                // Header: title + live connection status / connect button.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "TELEMETRY",
                        color = DashColors.Accent,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (connected) {
                        TextButton(onClick = onPickDevice, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(DashColors.Good)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Live", color = DashColors.Good, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Button(
                            onClick = onConnect,
                            enabled = connection != ObdConnectionState.CONNECTING,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DashColors.Accent,
                                contentColor = DashColors.OnAccent
                            ),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Bluetooth, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (connection == ObdConnectionState.CONNECTING) "\u2026" else "Connect")
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                val gauge: @Composable (Modifier) -> Unit = { m ->
                    AnalogGauge(
                        value = if (connected) obdData.speedKmh.toFloat() else 0f,
                        maxValue = 220f,
                        valueText = if (connected) obdData.speedKmh.toString() else "--",
                        label = "SPEED",
                        unit = "km/h",
                        accent = DashColors.Speed,
                        redlineAccent = DashColors.Warning,
                        redlineFraction = SPEED_WARNING_KMH / 220f,
                        dimmed = !connected,
                        majorTicks = 12,
                        hero = true,
                        modifier = m
                    )
                }
                val rpm: @Composable (Modifier) -> Unit = { m ->
                    RpmBar(rpm = obdData.rpm, maxRpm = 7000f, dimmed = !connected, modifier = m)
                }
                val chips: @Composable (Modifier) -> Unit = { m ->
                    MeterChip(
                        label = "Coolant",
                        valueText = if (connected) "${obdData.coolantTempC}\u00b0" else "--",
                        fraction = (obdData.coolantTempC / 120f),
                        color = coolantColor(obdData.coolantTempC),
                        dimmed = !connected,
                        modifier = m
                    )
                    MeterChip(
                        label = "Load",
                        valueText = if (connected) "${obdData.engineLoadPct}%" else "--",
                        fraction = obdData.engineLoadPct / 100f,
                        color = DashColors.Accent,
                        dimmed = !connected,
                        modifier = m
                    )
                    MeterChip(
                        label = "Battery",
                        valueText = if (connected) "%.1fV".format(obdData.voltage) else "--",
                        fraction = batteryFraction(obdData.voltage),
                        color = batteryColor(obdData.voltage),
                        dimmed = !connected,
                        modifier = m
                    )
                }

                if (stacked) {
                    gauge(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    rpm(Modifier.fillMaxWidth())
                    if (!compact) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) { chips(Modifier.weight(1f)) }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        gauge(Modifier.weight(1.25f).fillMaxHeight())
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                        ) {
                            rpm(Modifier.fillMaxWidth())
                            if (!compact) chips(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/**
 * Horizontal rev bar: green through the accent into red at the redline, with a
 * glow underlay on glowing themes and a marker at the redline.
 */
@Composable
internal fun RpmBar(
    rpm: Int,
    maxRpm: Float,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    redlineFraction: Float = 0.82f
) {
    val frac by animateFloatAsState(
        targetValue = if (dimmed) 0f else (rpm / maxRpm).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "rpmBar"
    )
    val good = DashColors.Good
    val accent = DashColors.Accent
    val warning = DashColors.Warning
    val glow = DashColors.Glow
    val track = if (DashColors.Glass) Color.Black.copy(alpha = 0.35f) else DashColors.Background
    val overRedline = frac >= redlineFraction

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RPM", color = DashColors.TextSecondary, letterSpacing = 1.5.sp, style = MaterialTheme.typography.labelSmall)
            Text(
                text = if (dimmed) "--" else rpm.toString(),
                color = if (dimmed) DashColors.Muted else if (overRedline) warning else DashColors.Rpm,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            val h = size.height
            val corner = CornerRadius(h / 2f)
            drawRoundRect(color = track, size = size, cornerRadius = corner)
            val w = size.width * frac
            if (w > 0f) {
                val fill = Brush.horizontalGradient(listOf(good, accent, warning), endX = size.width)
                if (glow > 0f) {
                    drawRoundRect(
                        brush = fill,
                        topLeft = Offset(0f, -h * 0.6f),
                        size = Size(w, h * 2.2f),
                        cornerRadius = CornerRadius(h),
                        alpha = 0.30f * glow
                    )
                }
                drawRoundRect(brush = fill, size = Size(w, h), cornerRadius = corner)
            }
            // Redline marker.
            val rx = size.width * redlineFraction
            drawLine(
                color = warning.copy(alpha = 0.7f),
                start = Offset(rx, -2f),
                end = Offset(rx, h + 2f),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
    }
}

internal fun coolantColor(tempC: Int): Color = when {
    tempC >= 105 -> DashColors.Warning
    tempC >= 75 -> DashColors.Good
    else -> DashColors.Speed
}

/**
 * A racing-style analog gauge: a 270° dark dial with tick marks, a coloured
 * sweep arc (turning red past [redlineFraction]), an animated needle and a big
 * digital readout in the middle. Scales to whatever size the tile gives it.
 */
@Composable
internal fun AnalogGauge(
    value: Float,
    maxValue: Float,
    valueText: String,
    label: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
    redlineAccent: Color = DashColors.Warning,
    redlineFraction: Float = 0.8f,
    dimmed: Boolean = false,
    majorTicks: Int = 9,
    // Hero style (telemetry speed): tick labels, glowing tip dot instead of a
    // needle, and large gradient numerals - the mockup's instrument cluster.
    hero: Boolean = false
) {
    if (DashColors.Original) {
        OriginalAnalogGauge(
            value, maxValue, valueText, label, unit, accent, modifier,
            redlineAccent, redlineFraction, dimmed, majorTicks
        )
        return
    }
    val target = (value / maxValue).coerceIn(0f, 1f)
    val frac by animateFloatAsState(
        targetValue = if (dimmed) 0f else target,
        animationSpec = tween(durationMillis = 500),
        label = "gauge"
    )
    val startAngle = 135f      // 7:30 position (Compose: 0° = 3 o'clock, CW positive)
    val sweepTotal = 270f
    val sweepColor = if (frac >= redlineFraction) redlineAccent else accent
    val needleColor = if (dimmed) DashColors.Muted else sweepColor
    val glass = DashColors.Glass
    val glow = DashColors.Glow
    val accent2 = DashColors.Accent2

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val gaugePx = min(maxWidth.value, maxHeight.value)
        val valueSize = if (hero) (gaugePx * 0.30f).coerceIn(22f, 76f).sp else (gaugePx * 0.20f).coerceIn(16f, 46f).sp
        val unitSize = (gaugePx * 0.075f).coerceIn(8f, 14f).sp
        val labelSize = (gaugePx * 0.085f).coerceIn(9f, 15f).sp
        val tickLabelSize = (gaugePx * 0.05f).coerceIn(7f, 12f).sp
        val textMeasurer = rememberTextMeasurer()
        val tickLabelColor = DashColors.TextSecondary.copy(alpha = 0.55f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.085f
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // Base track.
            drawArc(
                color = if (glass) Color.White.copy(alpha = 0.07f) else DashColors.CardHi,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Dim redline zone on the track.
            drawArc(
                color = redlineAccent.copy(alpha = 0.35f),
                startAngle = startAngle + sweepTotal * redlineFraction,
                sweepAngle = sweepTotal * (1f - redlineFraction),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Active sweep: accent->accent2 gradient along the arc, a wide soft
            // halo underneath (scaled by the theme's glow) and a bright core line.
            if (frac > 0f) {
                val sweepBrush: Brush = if (frac >= redlineFraction) SolidColor(redlineAccent)
                    else gaugeSweepBrush(center, accent, accent2)
                if (glow > 0f) {
                    drawArc(
                        brush = sweepBrush,
                        startAngle = startAngle,
                        sweepAngle = sweepTotal * frac,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        alpha = 0.14f * glow,
                        style = Stroke(width = stroke * 3.2f, cap = StrokeCap.Round)
                    )
                }
                drawArc(
                    brush = sweepBrush,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    alpha = 0.25f + 0.15f * glow,
                    style = Stroke(width = stroke * 1.9f, cap = StrokeCap.Round)
                )
                drawArc(
                    brush = sweepBrush,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.35f + 0.3f * glow),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 0.22f, cap = StrokeCap.Round)
                )
            }
            // Tick marks (hero adds minor ticks between the majors, plus labels).
            val tickOuter = radius - stroke * 0.6f
            val tickInner = radius - stroke * 1.5f
            val minorPerMajor = if (hero) 4 else 1
            val tickCount = (majorTicks - 1) * minorPerMajor
            for (i in 0..tickCount) {
                val major = i % minorPerMajor == 0
                val a = Math.toRadians((startAngle + sweepTotal * i / tickCount).toDouble())
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val inner = if (major) tickInner else tickInner + (tickOuter - tickInner) * 0.45f
                drawLine(
                    color = DashColors.TextSecondary.copy(alpha = if (major) 0.6f else 0.25f),
                    start = Offset(center.x + ca * inner, center.y + sa * inner),
                    end = Offset(center.x + ca * tickOuter, center.y + sa * tickOuter),
                    strokeWidth = stroke * if (major) 0.16f else 0.09f,
                    cap = StrokeCap.Round
                )
                // Tile-sized gauges label every other major and skip the two end
                // labels, which would collide with the unit text below the numerals.
                val majorIndex = i / minorPerMajor
                val showLabel = hero && major && (
                    gaugePx >= 300f || (majorIndex % 2 == 0 && i != 0 && i != tickCount)
                )
                if (showLabel) {
                    val labelValue = (maxValue * i / tickCount).roundToInt().toString()
                    val layout = textMeasurer.measure(
                        labelValue,
                        style = TextStyle(fontSize = tickLabelSize, fontWeight = FontWeight.SemiBold, color = tickLabelColor)
                    )
                    val lr = tickInner - stroke * 0.55f - maxOf(layout.size.width, layout.size.height) * 0.5f
                    drawText(
                        layout,
                        topLeft = Offset(
                            center.x + ca * lr - layout.size.width / 2f,
                            center.y + sa * lr - layout.size.height / 2f
                        )
                    )
                }
            }
            if (hero) {
                // Glowing tip dot at the end of the sweep.
                if (!dimmed) {
                    val tipA = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
                    val tip = Offset(center.x + cos(tipA).toFloat() * radius, center.y + sin(tipA).toFloat() * radius)
                    if (glow > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.9f * glow), Color.Transparent),
                                center = tip, radius = stroke * 1.6f
                            ),
                            radius = stroke * 1.6f, center = tip
                        )
                    }
                    drawCircle(color = Color.White, radius = stroke * 0.42f, center = tip)
                }
            } else {
                // Needle + hub.
                val needleA = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
                val nx = cos(needleA).toFloat()
                val ny = sin(needleA).toFloat()
                val needleLen = radius - stroke * 0.4f
                drawLine(
                    color = needleColor,
                    start = Offset(center.x - nx * radius * 0.12f, center.y - ny * radius * 0.12f),
                    end = Offset(center.x + nx * needleLen, center.y + ny * needleLen),
                    strokeWidth = stroke * 0.35f,
                    cap = StrokeCap.Round
                )
                drawCircle(color = DashColors.Card, radius = stroke * 0.9f, center = center)
                drawCircle(color = needleColor, radius = stroke * 0.5f, center = center)
            }
        }

        // Digital readout in the middle.
        val lit = glow > 0f && !dimmed
        // Hero numerals fade from white into the accent, like the mockup.
        val numeralBrush: Brush? = if (hero && lit) {
            Brush.verticalGradient(listOf(Color.White, lerp(Color.White, accent, 0.45f)))
        } else null
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = gaugePx.times(if (hero) 0.02f else 0.10f).dp)
        ) {
            Text(
                text = valueText,
                color = if (numeralBrush != null) Color.Unspecified else if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontSize = valueSize,
                fontWeight = if (hero) FontWeight.ExtraBold else FontWeight.Bold,
                letterSpacing = if (hero) (-0.06).em else (-0.04).em,
                maxLines = 1,
                // Numerals glow in the accent colour on glowing themes.
                style = TextStyle(
                    brush = numeralBrush,
                    shadow = if (lit) {
                        Shadow(color = accent.copy(alpha = 0.85f * glow), blurRadius = valueSize.value * 0.8f)
                    } else null
                )
            )
            Text(
                text = if (hero) unit.uppercase() else unit,
                color = if (hero) DashColors.TextSecondary else DashColors.Muted,
                fontSize = unitSize,
                fontWeight = if (hero) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = if (hero) 0.3.em else 0.15.em
            )
            if (!hero) {
                Spacer(Modifier.height(2.dp))
                Text(text = label, color = accent, fontSize = labelSize, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Small labelled meter: value on top, a rounded progress track below. */
@Composable
internal fun MeterChip(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    if (DashColors.Original) {
        OriginalMeterChip(label, valueText, fraction, color, dimmed, modifier)
        return
    }
    val glass = DashColors.Glass
    val chipShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(chipShape)
            .background(if (glass) Color.White.copy(alpha = 0.06f) else DashColors.CardHi)
            .border(1.dp, DashColors.Line, chipShape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(
                valueText,
                color = if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(if (glass) Color.Black.copy(alpha = 0.35f) else DashColors.Background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (dimmed) 0f else fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(color, lerp(color, Color.White, 0.3f))))
            )
        }
    }
}

/** Reads and clears OBD Diagnostic Trouble Codes (fault codes). */
@Composable
internal fun ObdDtcCard(
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val connected = connection == ObdConnectionState.CONNECTED
    var codes by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("FAULT CODES", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))

            if (!connected) {
                ObdNotConnected(onConnect)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true; message = null
                            scope.launch {
                                val r = ObdBluetoothManager.readTroubleCodes()
                                busy = false
                                r.onSuccess { codes = it; message = if (it.isEmpty()) "No fault codes ✓" else null }
                                    .onFailure { message = it.message ?: "Scan failed" }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.Background)
                    ) { Text("Scan") }
                    Button(
                        enabled = !busy && codes?.isNotEmpty() == true,
                        onClick = {
                            busy = true; message = null
                            scope.launch {
                                val res = ObdBluetoothManager.clearTroubleCodes()
                                busy = false
                                res.onSuccess { message = "Cleared ✓"; codes = emptyList() }
                                    .onFailure { message = it.message ?: "Clear failed" }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text("Clear") }
                }
                Spacer(Modifier.height(10.dp))
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DashColors.Accent)
                message?.let {
                    Text(it, color = if (it.contains("fail", true)) DashColors.Warning else DashColors.Good)
                    Spacer(Modifier.height(6.dp))
                }
                val list = codes
                if (list != null && list.isNotEmpty()) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        list.forEach { code ->
                            val dtc = ObdCodes.describe(code)
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text(
                                    "${dtc.code} — ${dtc.title}",
                                    color = DashColors.Warning,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    dtc.fix,
                                    color = DashColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shows every OBD value currently available. */
@Composable
internal fun ObdAllCard(
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = connection == ObdConnectionState.CONNECTED
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("OBD DATA", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            if (!connected) {
                ObdNotConnected(onConnect)
            } else {
                MeterChip("Speed", "${obdData.speedKmh} km/h", obdData.speedKmh / 220f, DashColors.Speed, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("RPM", "${obdData.rpm}", obdData.rpm / 7000f, DashColors.Rpm, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Coolant", "${obdData.coolantTempC} °C", obdData.coolantTempC / 120f, coolantColor(obdData.coolantTempC), false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Intake air", "${obdData.intakeTempC} °C", obdData.intakeTempC / 80f, DashColors.Accent, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Throttle", "${obdData.throttlePct} %", obdData.throttlePct / 100f, DashColors.Accent, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Engine load", "${obdData.engineLoadPct} %", obdData.engineLoadPct / 100f, DashColors.Rpm, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Fuel level", "${obdData.fuelLevelPct} %", obdData.fuelLevelPct / 100f, DashColors.Good, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Battery", "%.1f V".format(obdData.voltage), batteryFraction(obdData.voltage), batteryColor(obdData.voltage), false, Modifier.fillMaxWidth())
            }
        }
    }
}

// Citroën C4 Picasso rough figures for the estimate (until the CANbox gives the
// real distance-to-empty).
internal const val TANK_LITERS = 60.0

internal const val AVG_L_PER_100KM = 6.5

/**
 * Fuel & range as a radial gauge. Fuel level comes from the **CANbox** (learned
 * via the finder) when available — the C4 Picasso's OBD doesn't report it — and
 * falls back to the OBD fuel PID if that ever works. Range is estimated from the
 * tank size and average consumption until the CANbox gives a real distance.
 */
@Composable
internal fun RangeCard(
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The CANbox stream (needs root) is the real fuel source; keep it running
    // while this card is on screen so the learned byte decodes live.
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val canFuel by McuReader.fuelPercent.collectAsState()
    val obdConnected = connection == ObdConnectionState.CONNECTED
    val obdFuel = if (obdConnected) obdData.fuelLevelPct else 0

    // Prefer CANbox fuel; fall back to OBD; null when neither is available yet.
    val fuelPct: Int? = canFuel ?: obdFuel.takeIf { it > 0 }
    val source = if (canFuel != null) "via CANbox" else if (obdFuel > 0) "via OBD" else null

    var showFinder by remember { mutableStateOf(false) }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("FUEL & RANGE", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                source?.let {
                    Text(it, color = if (canFuel != null) DashColors.Good else DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (fuelPct == null) {
                // Nothing yet: explain and offer the finder / OBD connect.
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.LocalGasStation, null, tint = DashColors.Muted, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "This car's OBD doesn't report fuel. Learn it from the CANbox instead — needs root.",
                    color = DashColors.Muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showFinder = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.Background)
                ) {
                    Icon(Icons.Filled.Sensors, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Find fuel signal")
                }
                if (!obdConnected) {
                    TextButton(onClick = onConnect) {
                        Text("Try OBD fuel PID", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                val liters = fuelPct / 100.0 * TANK_LITERS
                val rangeKm = (liters / AVG_L_PER_100KM * 100).toInt()

                Spacer(Modifier.height(6.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnalogGauge(
                        value = fuelPct.toFloat(),
                        maxValue = 100f,
                        valueText = "$rangeKm",
                        label = "KM TO EMPTY",
                        unit = "≈ range",
                        accent = if (fuelPct <= 12) DashColors.Warning else DashColors.Good,
                        // No redline band on fuel (more fill = more fuel); the whole
                        // sweep just turns amber when the tank drops into reserve.
                        redlineFraction = 1f,
                        modifier = Modifier.fillMaxHeight()
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MeterChip("Fuel", "$fuelPct%", fuelPct / 100f, if (fuelPct <= 12) DashColors.Warning else DashColors.Good, false, Modifier.weight(1f))
                    MeterChip("In tank", "%.0f L".format(liters), (liters / TANK_LITERS).toFloat(), DashColors.Speed, false, Modifier.weight(1f))
                    MeterChip("Avg use", "%.1f".format(AVG_L_PER_100KM), 0.5f, DashColors.Accent, false, Modifier.weight(1f))
                }
                // Always reachable, so a learned mapping can be recalibrated or forgotten.
                TextButton(onClick = { showFinder = true }) {
                    Text(
                        if (canFuel == null) "Learn fuel from CANbox" else "Recalibrate fuel",
                        color = DashColors.Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    if (showFinder) {
        FuelFinderDialog(onDismiss = { showFinder = false })
    }
}

/**
 * Learns which CANbox/MCU byte is the fuel level with a **two-point capture**
 * (like the door A/B differential). A single snapshot is unreliable — many bytes
 * happen to read ~60% at one moment, including static ones — so instead the user
 * captures the frames at one fuel level, waits until the gauge has visibly
 * changed, captures again, and we only offer bytes that actually **moved in the
 * same direction** as the fuel and map consistently to the same full tank. That
 * excludes the static byte that got picked before and stayed at 60%.
 */
@Composable
internal fun FuelFinderDialog(onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val entries by McuReader.entries.collectAsState()
    // Capture A is restored from storage, so you can tap Capture A, close this,
    // drive for days, then reopen and Capture B against the same A.
    val savedA = remember { McuReader.loadFuelCaptureA() }
    var currentPct by remember { mutableIntStateOf(savedA?.first ?: 60) }
    var capA by remember { mutableStateOf(savedA?.second) }
    var pctA by remember { mutableIntStateOf(savedA?.first ?: 0) }
    var capB by remember { mutableStateOf<Map<String, List<Int>>?>(null) }
    var pctB by remember { mutableIntStateOf(0) }

    fun snapshot(): Map<String, List<Int>> = entries.associate { it.key to it.bytes }

    // Bytes that changed in the same direction as the fuel and map to a
    // consistent full-tank raw across both captures. err = disagreement between
    // the two implied full-tank values (lower = better fit).
    data class Cand(val key: String, val index: Int, val rawA: Int, val rawB: Int, val fullRaw: Int, val err: Int)
    val candidates = remember(capA, capB, pctA, pctB) {
        val a = capA; val b = capB
        if (a == null || b == null || pctA == pctB || pctA == 0 || pctB == 0) {
            emptyList()
        } else {
            val fuelDir = if (pctB > pctA) 1 else -1
            val out = ArrayList<Cand>()
            for ((key, av) in a) {
                val bv = b[key] ?: continue
                val n = minOf(av.size, bv.size)
                for (i in 0 until n) {
                    val ra = av[i]; val rb = bv[i]
                    if (ra !in 1..255 || rb !in 1..255 || ra == rb) continue
                    val byteDir = if (rb > ra) 1 else -1
                    if (byteDir != fuelDir) continue                       // must track fuel
                    val fullA = ra * 100f / pctA
                    val fullB = rb * 100f / pctB
                    val fullRaw = ((fullA + fullB) / 2f).roundToInt().coerceIn(1, 255)
                    if (fullRaw < maxOf(ra, rb)) continue                  // full tank ≥ current
                    out.add(Cand(key, i, ra, rb, fullRaw, kotlin.math.abs(fullA - fullB).roundToInt()))
                }
            }
            out.sortedWith(compareBy({ it.err }, { -kotlin.math.abs(it.rawB - it.rawA) })).take(12)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text("Find fuel signal", color = DashColors.TextPrimary) },
        text = {
            Column {
                Text(
                    "Set your dash gauge %, tap Capture A — then close this and use the car normally. Days later, once the gauge has dropped a few %, reopen, set the new value and tap Capture B. Capture A is saved across restarts. Only bytes that moved with the fuel are offered.",
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dash reads", color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    FilledIconButton(
                        onClick = { currentPct = (currentPct - 5).coerceAtLeast(5) },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text("−") }
                    Text("$currentPct%", color = DashColors.TextPrimary, fontWeight = FontWeight.Bold)
                    FilledIconButton(
                        onClick = { currentPct = (currentPct + 5).coerceAtMost(100) },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text("+") }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val snap = snapshot(); capA = snap; pctA = currentPct
                            McuReader.saveFuelCaptureA(currentPct, snap)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Accent)
                    ) { Text(if (capA == null) "Capture A" else "A ✓ $pctA%") }
                    TextButton(
                        onClick = { capB = snapshot(); pctB = currentPct },
                        enabled = capA != null,
                        colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Accent)
                    ) { Text(if (capB == null) "Capture B" else "B ✓ $pctB%") }
                    if (capA != null || capB != null) {
                        TextButton(
                            onClick = {
                                capA = null; capB = null; pctA = 0; pctB = 0
                                McuReader.clearFuelCaptureA()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Muted)
                        ) { Text("Reset") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                when {
                    entries.isEmpty() ->
                        Text("Waiting for CANbox data… (needs root)", color = DashColors.Muted)
                    capA == null ->
                        Text("Set your current fuel %, then tap Capture A.", color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                    capB == null ->
                        Text("Captured A at $pctA% (saved). You can close this and drive normally — come back once the gauge drops a few %, set the new value, then Capture B.", color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                    pctA == pctB ->
                        Text("A and B are the same %. Capture B at a different fuel level.", color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                    candidates.isEmpty() ->
                        Text("No byte tracked the change. Recapture B after a bigger drop.", color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text("Bytes that moved with the fuel", color = DashColors.Accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(210.dp)) {
                            lazyColumnItems(candidates, key = { "${it.key}#${it.index}" }) { c ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DashColors.CardHi)
                                        .clickable {
                                            McuReader.saveFuelMapping(c.key, c.index, c.fullRaw)
                                            McuReader.clearFuelCaptureA()
                                            onDismiss()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("frame ${c.key}  ·  byte ${c.index}", color = DashColors.TextPrimary, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                        Text("${c.rawA} → ${c.rawB}  ·  full≈${c.fullRaw}", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Icon(Icons.Filled.LocalGasStation, null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (McuReader.fuelConfigured) {
                TextButton(onClick = { McuReader.clearFuelMapping(); onDismiss() }) {
                    Text("Forget current", color = DashColors.Warning)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = DashColors.Muted) }
        }
    )
}

/**
 * Gradient that follows the gauge arc (135deg -> 405deg). Compose sweep gradients
 * start at 3 o'clock, so the stops are placed in that frame: the arc start
 * (135deg = 0.375) is [start], the arc end (45deg = 0.125, wrapped) is [end].
 */
internal fun gaugeSweepBrush(center: Offset, start: Color, end: Color): Brush {
    val mid = lerp(start, end, 0.35f)
    val atZero = lerp(mid, end, 0.65f)
    return Brush.sweepGradient(
        0f to atZero,
        0.125f to end,
        0.375f to start,
        0.7875f to mid,
        1f to atZero,
        center = center
    )
}

/** Lets the user pick which paired Bluetooth device is the OBD adapter. */
@Composable
internal fun DevicePickerDialog(
    devices: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text("Select OBD adapter", color = DashColors.TextPrimary) },
        text = {
            Column {
                if (devices.isEmpty()) {
                    Text(
                        "No paired Bluetooth devices. Pair your OBD adapter in Bluetooth settings first.",
                        color = DashColors.TextSecondary
                    )
                } else {
                    devices.forEach { (name, mac) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(mac) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(name, color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
                            Text(mac, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Bluetooth settings", color = DashColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = DashColors.Muted)
            }
        }
    )
}

// Only BLUETOOTH_CONNECT is needed (and declared) to talk to a bonded adapter.
internal fun requiredBluetoothPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }
