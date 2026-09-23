package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DashThemePickerDialog(
    selected: DashThemeMode,
    appearance: DashAppearance,
    onSelect: (DashThemeMode) -> Unit,
    onAppearance: (DashAppearance) -> Unit,
    onDismiss: () -> Unit
) {
    // Previews show the version on screen now, so Auto previews follow the car too.
    val light = appearance.isLight()
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.dash_theme_picker_title), color = DashColors.TextPrimary) },
        text = {
            // Twelve options outgrow a 720p head unit; let the list scroll.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppearanceSwitch(appearance, onAppearance)
                Text(
                    stringResource(
                        when (appearance) {
                            DashAppearance.AUTO -> R.string.dash_appearance_auto_hint
                            DashAppearance.DARK -> R.string.dash_appearance_dark_hint
                            DashAppearance.LIGHT -> R.string.dash_appearance_light_hint
                        }
                    ),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(Modifier.height(4.dp))
                DashThemeMode.entries.forEach { mode ->
                    ThemeOption(mode, light, mode == selected) { onSelect(mode) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_done), color = DashColors.Accent) } }
    )
}

/** Auto / Dark / Light segmented switch; the chosen segment wears the accent gradient. */
@Composable
private fun AppearanceSwitch(appearance: DashAppearance, onAppearance: (DashAppearance) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.5f), shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DashAppearance.entries.forEach { option ->
            val chosen = option == appearance
            val segment = RoundedCornerShape(10.dp)
            val ink = if (chosen) DashColors.OnAccent else DashColors.TextPrimary
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(segment)
                    .then(if (chosen) Modifier.background(DashColors.AccentBrush, segment) else Modifier)
                    .clickable { onAppearance(option) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (option) {
                        DashAppearance.AUTO -> Icons.Filled.BrightnessAuto
                        DashAppearance.DARK -> Icons.Filled.DarkMode
                        DashAppearance.LIGHT -> Icons.Filled.LightMode
                    },
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(option.titleRes), color = ink, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ThemeOption(mode: DashThemeMode, light: Boolean, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier.fillMaxWidth().border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.CardHi, shape)
            // Scale (not replace) the alpha: glass themes use a translucent CardHi.
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.65f else 0.35f), shape)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 54.dp, height = 38.dp)
                .background(previewBrush(mode, light), RoundedCornerShape(10.dp))
                .border(1.dp, DashColors.Line, RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(mode.titleRes), color = DashColors.TextPrimary)
            Text(stringResource(mode.descriptionRes), color = DashColors.TextSecondary)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent)
    }
}

/** Swatch for [mode] in its dark or [light] version. */
private fun previewBrush(mode: DashThemeMode, light: Boolean): Brush = if (light) when (mode) {
    DashThemeMode.AUTO -> Brush.linearGradient(listOf(Color(0xFFF1F3F4), Color(0xFF1A73E8), Color(0xFF7B4DFF)))
    DashThemeMode.ORIGINAL -> Brush.linearGradient(listOf(Color(0xFFF1F3F4), Color.White, Color(0xFF1A73E8)))
    DashThemeMode.AURORA -> Brush.linearGradient(listOf(Color(0xFFE6F0FF), Color(0xFF0092D6), Color(0xFF7B5CF0)))
    DashThemeMode.NEON_DARK -> Brush.linearGradient(listOf(Color(0xFFEBF0FF), Color(0xFF2F6BFF), Color(0xFF8A4DFF)))
    DashThemeMode.CLEAN_LIGHT -> Brush.linearGradient(listOf(Color.White, Color(0xFFDCE8F7)))
    DashThemeMode.DARK_GLASS -> Brush.linearGradient(listOf(Color(0xFFF5F7FA), Color(0xFFC9D8EE), Color(0xFF2F72D6)))
    DashThemeMode.SPORTY -> Brush.linearGradient(listOf(Color.White, Color(0xFFE0162E)))
    DashThemeMode.FLOATING -> Brush.linearGradient(listOf(Color(0xFFE6EEFA), Color(0xFFF6F8FB), Color(0xFF1C7FD6)))
    DashThemeMode.ORBIT -> Brush.radialGradient(listOf(Color(0xFFF0603F), Color(0xFF6B5CF0), Color(0xFFF4F1FA)))
    DashThemeMode.COCKPIT -> Brush.linearGradient(listOf(Color(0xFFE3D5C1), Color(0xFFD8D5CF), Color(0xFFD9660A)))
    DashThemeMode.HORIZON -> Brush.verticalGradient(listOf(Color(0xFF8FC1EE), Color(0xFFCFE3F5), Color(0xFFFFF6E8), Color(0xFFE2D5B8)))
    DashThemeMode.TAPE_DECK -> Brush.verticalGradient(listOf(Color(0xFFFFE3F0), Color(0xFFFF7EAA), Color(0xFF00A0B4)))
} else when (mode) {
    DashThemeMode.AUTO -> Brush.linearGradient(listOf(Color(0xFF0B0C0F), Color(0xFF2A2D33), Color(0xFFC58AF9)))
    DashThemeMode.ORIGINAL -> Brush.linearGradient(listOf(Color(0xFF0B0C0F), Color(0xFF1E2024), Color(0xFF8AB4F8)))
    DashThemeMode.AURORA -> Brush.linearGradient(listOf(Color(0xFF0E1730), Color(0xFF5AD0FF), Color(0xFF9B7BFF)))
    DashThemeMode.NEON_DARK -> Brush.linearGradient(listOf(Color(0xFF071126), Color(0xFF6D3CFF)))
    DashThemeMode.CLEAN_LIGHT -> Brush.linearGradient(listOf(Color(0xFF0F1115), Color(0xFF1B1F25), Color(0xFF6AA3F0)))
    DashThemeMode.DARK_GLASS -> Brush.linearGradient(listOf(Color(0xFF05070B), Color(0xFF233A5F)))
    DashThemeMode.SPORTY -> Brush.linearGradient(listOf(Color(0xFF08090B), Color(0xFFFF334A)))
    DashThemeMode.FLOATING -> Brush.linearGradient(listOf(Color(0xFF0C1424), Color(0xFF06080D), Color(0xFF7CC4FF)))
    DashThemeMode.ORBIT -> Brush.radialGradient(listOf(Color(0xFFFF7A59), Color(0xFF8A7BFF), Color(0xFF0A0E1C)))
    DashThemeMode.COCKPIT -> Brush.linearGradient(listOf(Color(0xFF231C16), Color(0xFFD8D5CF), Color(0xFFFF8A1F)))
    DashThemeMode.HORIZON -> Brush.verticalGradient(listOf(Color(0xFF0A0F2C), Color(0xFF8A3F72), Color(0xFFF9B274), Color(0xFF1A1030)))
    DashThemeMode.TAPE_DECK -> Brush.verticalGradient(listOf(Color(0xFF0D0221), Color(0xFFFF2A6D), Color(0xFF05D9E8)))
}
