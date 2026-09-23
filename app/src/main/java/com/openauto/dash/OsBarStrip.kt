package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.roundToInt

/*
 * The strip behind the head unit's own status bar. Android forces that bar on
 * while a floating window is docked, and the bar takes every tap on its strip,
 * so nothing interactive can live there. Instead of sitting empty above the
 * dashboard, the strip carries our read-only info in the gap between the OS
 * home button (left) and the OS status icons (right): the OBD link, the outside
 * temperature on skins whose bar shows one, the page dots and any warning. The
 * launcher bar meanwhile keeps only its buttons (TopBarModel.merged) and drops
 * its clock, since the OS bar already shows the time.
 */

/** Room the head unit's home button takes at the strip's left edge. */
private val OS_HOME_CLEARANCE = 104.dp

/** Where the head unit's status icons (Bluetooth … back) begin, as a fraction of the width. */
private const val OS_ICONS_START = 0.6f

/** The head unit draws its icons white whatever our theme, so the strip is always dark and its text light. */
private val StripInk = Color.White.copy(alpha = 0.92f)

@Composable
internal fun OsBarStrip(
    height: Dp,
    page: Int,
    pageCount: Int,
    obdConnection: ObdConnectionState,
    obdData: ObdData
) {
    // A dark band on day themes too, so the OS icons stay readable; on night
    // themes it only deepens the page a little.
    val shade = Color.Black.copy(alpha = if (DashColors.Light) 0.55f else 0.22f)
    val rule = Color.White.copy(alpha = 0.08f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawRect(shade)
                drawLine(rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }
    ) {
        Row(
            modifier = Modifier
                .padding(start = OS_HOME_CLEARANCE)
                .width((maxWidth * OS_ICONS_START - OS_HOME_CLEARANCE).coerceAtLeast(0.dp))
                .fillMaxHeight()
                .clipToBounds(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StripObd(obdConnection)
            if (DashColors.Skin == DashSkin.COCKPIT || DashColors.Skin == DashSkin.TAPE_DECK) StripOutsideTemp()
            StripPageDots(page, pageCount)
            VehicleAlerts(obdConnection, obdData) { icon, text -> StripAlert(icon, text) }
        }
    }
}

/** Day palettes darken their status colours for pale pages; lift them back for the dark strip. */
private fun stripTone(color: Color): Color = if (DashColors.Light) lerp(color, Color.White, 0.35f) else color

/** OBD link as a lamp and label; it connects by itself, or from the ⋮ menu while the strip is up. */
@Composable
private fun StripObd(state: ObdConnectionState) {
    val color = stripTone(obdStatusColor(state))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .drawBehind {
                    val r = size.minDimension / 2f
                    when (state) {
                        ObdConnectionState.CONNECTED -> {
                            drawCircle(color.copy(alpha = 0.35f), radius = r * 2f)
                            drawCircle(color, radius = r)
                        }
                        ObdConnectionState.DISCONNECTED -> {
                            val ring = 1.5.dp.toPx()
                            drawCircle(color, radius = r - ring / 2f, style = Stroke(ring))
                        }
                        else -> drawCircle(color, radius = r)
                    }
                }
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "OBD",
            color = StripInk,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.08.em,
            maxLines = 1
        )
    }
}

/** Where we are among the dashboards; swiping the bottom bar or the pages changes it. */
@Composable
private fun StripPageDots(page: Int, count: Int) {
    val active = Brush.linearGradient(listOf(stripTone(DashColors.Accent), stripTone(DashColors.Accent2)))
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val current = index == page
            Box(
                Modifier
                    .size(width = if (current) 16.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .then(if (current) Modifier.background(active) else Modifier.background(StripInk.copy(alpha = 0.3f)))
            )
        }
    }
}

/** Outside temperature from the weather feed; nothing until the first fetch. */
@Composable
private fun StripOutsideTemp() {
    val weather = rememberWeather() ?: return
    Text(
        "${weather.tempC.roundToInt()}°C",
        color = StripInk,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

/** Compact warning pill that fits the strip's height. */
@Composable
private fun StripAlert(icon: ImageVector, text: String) {
    val tone = stripTone(DashColors.Warning)
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(tone.copy(alpha = 0.18f))
            .border(1.dp, tone.copy(alpha = 0.5f), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = tone, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
