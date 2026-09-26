package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/*
 * The Look settings: the appearance switch, the effects switch, and the
 * themes as a gallery of small dashboards drawn with each theme's own
 * palette, night half and day half side by side, so the choice is made on
 * what the screen will look like rather than on a name.
 */

/** The three the picker leads with: the default, and the two made for the car. */
private val Recommended = listOf(DashThemeMode.AUTO, DashThemeMode.MISTRAL, DashThemeMode.ZENITH)

@Composable
internal fun ThemePane(theme: ThemeState) {
    SettingsSection(stringResource(R.string.dash_theme_picker_title))
    SegmentedSwitch(
        options = DashAppearance.entries,
        chosen = theme.appearance,
        icon = { option ->
            when (option) {
                DashAppearance.AUTO -> Icons.Filled.BrightnessAuto
                DashAppearance.DARK -> Icons.Filled.DarkMode
                DashAppearance.LIGHT -> Icons.Filled.LightMode
            }
        },
        title = { stringResource(it.titleRes) },
        onChoose = theme.onAppearance
    )
    SwitchHint(
        stringResource(
            when (theme.appearance) {
                DashAppearance.AUTO -> R.string.dash_appearance_auto_hint
                DashAppearance.DARK -> R.string.dash_appearance_dark_hint
                DashAppearance.LIGHT -> R.string.dash_appearance_light_hint
            }
        )
    )
    Spacer(Modifier.height(12.dp))
    // Effects: how much halo and glass a theme draws. Off is the
    // high-legibility setting for a dim screen in full sun.
    Text(
        stringResource(R.string.dash_effects_title),
        color = DashColors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
    SegmentedSwitch(
        options = DashEffects.entries,
        chosen = theme.effects,
        icon = { option ->
            when (option) {
                DashEffects.NONE -> Icons.Filled.VisibilityOff
                DashEffects.REDUCED -> Icons.Filled.Tonality
                DashEffects.FULL -> Icons.Filled.AutoAwesome
            }
        },
        title = { stringResource(it.titleRes) },
        onChoose = theme.onEffects
    )
    SwitchHint(stringResource(theme.effects.hintRes))
    Spacer(Modifier.height(12.dp))
    BarAutoHideSetting(theme)
    Spacer(Modifier.height(20.dp))

    val skins = DashThemeMode.entries.filter { paletteFor(it, false).Skin != DashSkin.STANDARD }
    val colours = DashThemeMode.entries.filter { it !in Recommended && it !in skins }
    ThemeGroup(stringResource(R.string.dash_theme_group_recommended), Recommended, theme)
    ThemeGroup(stringResource(R.string.dash_theme_group_colours), colours, theme)
    ThemeGroup(stringResource(R.string.dash_theme_group_skins), skins, theme)
}

/**
 * The bottom bar: always there, or hiding itself once unused for the chosen
 * 0 to 20 seconds and coming back with a swipe up (BarAutoHide.kt).
 */
@Composable
private fun BarAutoHideSetting(theme: ThemeState) {
    Text(
        stringResource(R.string.dash_bar_title),
        color = DashColors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
    SettingsToggle(
        icon = Icons.Filled.VerticalAlignBottom,
        title = stringResource(R.string.dash_bar_auto_hide),
        detail = stringResource(R.string.dash_bar_auto_hide_detail),
        checked = theme.barAutoHide,
        onChange = theme.onBarAutoHide
    )
    if (!theme.barAutoHide) return
    // Follows the thumb while dragging; saved once it is let go.
    var seconds by remember(theme.barHideSeconds) { mutableFloatStateOf(theme.barHideSeconds.toFloat()) }
    val whole = seconds.roundToInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Timer, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            if (whole == 0) stringResource(R.string.dash_bar_hide_now) else stringResource(R.string.dash_bar_hide_after, whole),
            color = DashColors.TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 170.dp)
        )
        Spacer(Modifier.width(12.dp))
        Slider(
            value = seconds,
            onValueChange = { seconds = it },
            onValueChangeFinished = { theme.onBarHideSeconds(seconds.roundToInt()) },
            valueRange = 0f..MAX_BAR_HIDE_SECONDS.toFloat(),
            steps = MAX_BAR_HIDE_SECONDS - 1,
            colors = SliderDefaults.colors(
                thumbColor = if (DashColors.Light) DashColors.Accent else Color.White,
                activeTrackColor = DashColors.Accent,
                inactiveTrackColor = DashColors.CardHi,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.weight(1f)
        )
    }
    SwitchHint(stringResource(R.string.dash_bar_swipe_up_detail))
}

/** A titled group of themes, three to a row. */
@Composable
private fun ThemeGroup(title: String, modes: List<DashThemeMode>, theme: ThemeState) {
    SettingsSection(title)
    modes.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { mode ->
                ThemeThumbnail(mode, mode == theme.mode, Modifier.weight(1f)) { theme.onMode(mode) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** One theme: its night and day dashboards side by side, its name and its one-line description. */
@Composable
private fun ThemeThumbnail(mode: DashThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = DashShape.Medium
    val tap = rememberTapFeedback()
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.3f))
            .clickable { tap(); onClick() }
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f)
                .clip(DashShape.Small)
        ) {
            MiniDashboard(paletteFor(mode, light = false), stringResource(R.string.dash_theme_night), Modifier.weight(1f).fillMaxHeight())
            MiniDashboard(paletteFor(mode, light = true), stringResource(R.string.dash_theme_day), Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(mode.titleRes),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent, modifier = Modifier.size(20.dp))
        }
        Text(
            stringResource(mode.descriptionRes),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A dashboard the size of a stamp, drawn straight from [p]: the page
 * gradient, the bar with its clock, a speed tile and a tile of three meters.
 * Skins are drawn with their palette too; their own shapes are not redrawn here.
 */
@Composable
private fun MiniDashboard(p: DashPalette, caption: String, modifier: Modifier) {
    val card = RoundedCornerShape(5.dp)
    val cardFill = if (p.Bare) p.Background.copy(alpha = 0f) else p.Card
    Column(
        modifier = modifier
            .background(Brush.linearGradient(p.BackgroundStops))
            .padding(5.dp)
    ) {
        // Bar: a clock in the middle, a live dot on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (p.Bar.alpha < 0.05f) p.Background.copy(alpha = 0f) else p.Bar),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Text("12:34", color = p.TextPrimary, fontSize = 7.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(p.Good))
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Speed tile: the hero numeral in the accent.
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .clip(card)
                    .background(cardFill)
                    .border(1.dp, p.Line, card)
                    .padding(4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("87", color = p.Accent, fontSize = 17.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.04).em)
                Text("KM/H", color = p.TextSecondary, fontSize = 5.sp, lineHeight = 6.sp, letterSpacing = 0.15.em)
            }
            // Meters tile: the second colour, the tachometer amber and the good green.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(card)
                    .background(cardFill)
                    .border(1.dp, p.Line, card)
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                MiniMeter(p, p.Secondary, 0.7f)
                MiniMeter(p, p.Tacho, 0.45f)
                MiniMeter(p, p.Good, 0.85f)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(caption, color = p.Muted, fontSize = 6.sp, lineHeight = 7.sp, letterSpacing = 0.1.em, modifier = Modifier.align(Alignment.End))
    }
}

/** A meter's track and fill, three pixels tall. */
@Composable
private fun MiniMeter(p: DashPalette, colour: Color, fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(p.CardHi)
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colour))
    }
}

/** One line under a switch saying what the chosen segment does. */
@Composable
internal fun SwitchHint(text: String) {
    Text(
        text,
        color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** Segmented switch over [options]; the chosen segment wears the accent gradient. */
@Composable
internal fun <T> SegmentedSwitch(
    options: List<T>,
    chosen: T,
    icon: (T) -> ImageVector,
    title: @Composable (T) -> String,
    onChoose: (T) -> Unit
) {
    val shape = DashShape.Medium
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.5f), shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val picked = option == chosen
            val segment = DashShape.Small
            val ink = if (picked) DashColors.OnAccent else DashColors.TextPrimary
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(segment)
                    .then(if (picked) Modifier.background(DashColors.AccentBrush, segment) else Modifier)
                    .clickable { tap(); onChoose(option) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon(option), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(title(option), color = ink, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}
