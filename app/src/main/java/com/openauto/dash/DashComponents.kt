@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
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
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f)))
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
 * the aurora background show through.
 */
@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    if (DashColors.Glass) {
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
 * Glass surface: translucent white->accent gradient fill, hairline border and a
 * specular highlight along the top edge. The head unit is Android 10, so there
 * is no RenderEffect backdrop blur; the layered translucency carries the look.
 */
@Composable
internal fun glassPanel(shape: RoundedCornerShape): Modifier {
    val line = DashColors.Line
    val accent = DashColors.Accent
    return Modifier
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.10f),
                    Color.White.copy(alpha = 0.035f),
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

internal fun currentClock(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
