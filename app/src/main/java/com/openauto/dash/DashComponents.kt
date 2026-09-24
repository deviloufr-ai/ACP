@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.BorderStroke

/*
 * Shared surfaces and controls: card, glass panel, page background, round buttons, helpers.
 */

/** Secondary round control: frosted disc with a hairline rim. */
@Composable
internal fun GlassRoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val fill: Brush = if (DashColors.Glass) {
        // By day the frost has to be dense to stand off the pale page.
        val (top, bottom) = if (DashColors.Light) 0.9f to 0.6f else 0.16f to 0.05f
        Brush.linearGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom)))
    } else SolidColor(DashColors.CardHi)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, DashColors.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = DashColors.TextPrimary, modifier = Modifier.size(iconSize))
    }
}

/** Primary round control: accent-gradient disc with a halo that scales with the theme's glow. */
@Composable
internal fun GradientRoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val accent = DashColors.Accent
    val glow = DashColors.Glow
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                if (glow > 0f) {
                    val r = this.size.minDimension * 0.85f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.45f * glow), Color.Transparent),
                            center = center, radius = r
                        ),
                        radius = r
                    )
                }
            }
            .clip(CircleShape)
            .background(DashColors.AccentBrush)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = DashColors.OnAccent, modifier = Modifier.size(iconSize))
    }
}

/**
 * Rounded elevated card. Solid themes use a flat surface matching the Android
 * Auto content cards; glass themes use a translucent gradient panel that lets
 * the aurora background show through; bare themes draw no panel at all.
 */
@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    if (DashColors.Bare) {
        // Unclipped: with no panel edge to hide it, a clip would cut glows off in a hard line.
        Box(modifier = modifier) { content() }
    } else if (DashColors.Glass) {
        Box(modifier = modifier.then(glassPanel(shape))) { content() }
    } else {
        Surface(
            modifier = modifier,
            color = DashColors.Card,
            shape = shape,
            content = content
        )
    }
}

/**
 * Opaque card for overlays (the app drawer, sheets) that must hide whatever is
 * behind them on every theme. Glass themes make the normal card translucent,
 * which is right for tiles over the background but wrong for a panel over tiles.
 */
@Composable
internal fun SolidCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = DashColors.Card.copy(alpha = 1f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, DashColors.Line),
        content = content
    )
}

/**
 * Fill and hairline rim behind an item on a tile: an icon disc, a chip, a list
 * row. Bare themes drop both so the item sits straight on the page background.
 */
internal fun Modifier.itemFill(fill: Color, shape: Shape, rim: Color? = DashColors.Line): Modifier =
    if (DashColors.Bare) this
    else background(fill, shape).then(if (rim != null) Modifier.border(1.dp, rim, shape) else Modifier)

/**
 * Glass surface: translucent white->accent gradient fill, hairline border and a
 * specular highlight along the top edge. The head unit is Android 10, so there
 * is no RenderEffect backdrop blur; the layered translucency carries the look.
 * Light themes use frosted glass: the same layers, but a dense white fill.
 */
@Composable
internal fun glassPanel(shape: RoundedCornerShape): Modifier {
    val line = DashColors.Line
    val accent = DashColors.Accent
    val (top, bottom) = if (DashColors.Light) 0.78f to 0.52f else 0.10f to 0.035f
    return Modifier
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = top),
                    Color.White.copy(alpha = bottom),
                    accent.copy(alpha = 0.06f)
                )
            )
        )
        .border(1.dp, line, shape)
        .drawWithContent {
            drawContent()
            val inset = size.width * 0.18f
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                    startX = inset, endX = size.width - inset
                ),
                start = Offset(inset, 1f),
                end = Offset(size.width - inset, 1f),
                strokeWidth = 1.5f
            )
        }
}

/**
 * Page background: the theme's gradient, plus (on glass themes) a cool wash
 * from the top and a violet wash from the bottom-right corner.
 */
@Composable
internal fun dashBackground(): Modifier {
    if (DashColors.Skin != DashSkin.STANDARD) return skinBackground()
    val stops = DashColors.BackgroundStops
    val glass = DashColors.Glass
    val glow = DashColors.Glow
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    return Modifier.drawBehind {
        drawRect(
            brush = Brush.linearGradient(
                colors = stops,
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
        if (glass) {
            val topC = Offset(size.width * 0.5f, -size.height * 0.15f)
            val topR = size.width * 0.45f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.22f * glow), Color.Transparent),
                    center = topC, radius = topR
                ),
                radius = topR, center = topC
            )
            val cornerC = Offset(size.width * 1.05f, size.height * 1.05f)
            val cornerR = size.width * 0.40f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent2.copy(alpha = 0.24f * glow), Color.Transparent),
                    center = cornerC, radius = cornerR
                ),
                radius = cornerR, center = cornerC
            )
        }
    }
}

/** Average colour of a bitmap (used for the album-art colour bleed). */
internal fun Bitmap.averageColor(): Color = runCatching {
    Color(Bitmap.createScaledBitmap(this, 1, 1, true).getPixel(0, 0))
}.getOrDefault(Color.Gray)

/**
 * Starts [intent] as a new task, swallowing the ActivityNotFound / permission
 * failures a stripped head-unit ROM can throw. True if it started.
 */
internal fun Context.launchSafely(intent: Intent): Boolean =
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

/**
 * True on an Android emulator. The GL-heavy tiles (MapLibre map, Filament 3D
 * car) crash natively on the emulator's software renderer, so they show a
 * placeholder there; everything else stays testable on an AVD.
 */
internal val isEmulator: Boolean by lazy {
    val fp = android.os.Build.FINGERPRINT.lowercase()
    val model = android.os.Build.MODEL.lowercase()
    val product = android.os.Build.PRODUCT.lowercase()
    val hw = android.os.Build.HARDWARE.lowercase()
    fp.startsWith("generic") || fp.contains("emulator") || fp.contains("emu64") ||
        model.contains("emulator") || model.contains("android sdk built for") ||
        product.contains("sdk") || product.startsWith("emu") ||
        hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("cutf")
}

/**
 * Vertical grab bar between the Maps dock and the dashboard pages. Horizontal
 * drags report pixel deltas; the caller converts them into a width share.
 */
@Composable
internal fun DockDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(24.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                ) { change, dx ->
                    change.consume()
                    onDrag(dx)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DashColors.TextSecondary.copy(alpha = 0.55f))
        )
    }
}

internal fun currentClock(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
