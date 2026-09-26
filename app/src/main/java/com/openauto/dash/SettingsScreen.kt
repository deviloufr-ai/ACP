package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/*
 * The Settings screen: everything set once, full screen in two columns like
 * a car's own settings. Categories on the left, the chosen one's settings on
 * the right, most of them right there (theme, appearance, language, driving,
 * the paired phone) and the deep ones (car profile, AI, upkeep, readings, boot logo) one tap
 * away in their own sheet. Replaces the dialogs that used to stack four deep.
 */

internal enum class SettingsTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    CAR(R.string.settings_section_car, Icons.Filled.DirectionsCar),
    LOOK(R.string.settings_section_look, Icons.Filled.Palette),
    DRIVING(R.string.settings_section_driving, Icons.Filled.Speed),
    PHONE(R.string.settings_section_phone, Icons.Filled.PhoneAndroid),
    ADVANCED(R.string.settings_section_advanced, Icons.Filled.Tune)
}

/** The theme choice and its setters, owned by the dashboard root. */
internal data class ThemeState(
    val mode: DashThemeMode,
    val appearance: DashAppearance,
    val effects: DashEffects,
    val onMode: (DashThemeMode) -> Unit,
    val onAppearance: (DashAppearance) -> Unit,
    val onEffects: (DashEffects) -> Unit
)

@Composable
internal fun SettingsScreen(
    m: TopBarModel,
    theme: ThemeState,
    initialTab: SettingsTab,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var carSettings by remember { mutableStateOf(false) }
    var aiSettings by remember { mutableStateOf(false) }
    var upkeep by remember { mutableStateOf(false) }
    var explorer by remember { mutableStateOf(false) }
    var bootLogo by remember { mutableStateOf(false) }
    val tap = rememberTapFeedback()

    SolidCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left: close, title, the categories.
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.4f))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(DashShape.Medium)
                            .background(DashColors.CardHi)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_close)) { tap(); onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dash_close), tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(R.string.settings_title),
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsTab.entries.forEach { t ->
                    val chosen = t == tab
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(DashShape.Medium)
                            .then(if (chosen) Modifier.background(DashColors.AccentBrush) else Modifier)
                            .clickable(role = Role.Tab) { tab = t }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ink = if (chosen) DashColors.OnAccent else DashColors.TextPrimary
                        Icon(t.icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(t.titleRes), color = ink, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // Right: the chosen category's settings.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                when (tab) {
                    SettingsTab.CAR -> CarPane(
                        m,
                        onCar = { carSettings = true },
                        onAi = { aiSettings = true },
                        onUpkeep = { upkeep = true },
                        onExplorer = { explorer = true }
                    )
                    SettingsTab.LOOK -> LookPane(theme)
                    SettingsTab.DRIVING -> DrivingPane(m)
                    SettingsTab.PHONE -> PhonePane()
                    SettingsTab.ADVANCED -> AdvancedPane(m, onBootLogo = { bootLogo = true })
                }
            }
        }
    }

    if (carSettings) CarSettingsDialog(onDismiss = { carSettings = false })
    if (aiSettings) AiSettingsDialog(onDismiss = { aiSettings = false })
    if (upkeep) UpkeepDialog(onDismiss = { upkeep = false })
    if (explorer) PidExplorerDialog(onDismiss = { explorer = false })
    if (bootLogo) BootLogoDialog(onDismiss = { bootLogo = false })
}

@Composable
private fun CarPane(m: TopBarModel, onCar: () -> Unit, onAi: () -> Unit, onUpkeep: () -> Unit, onExplorer: () -> Unit) {
    val car by CarProfileStore.profile.collectAsState()
    SettingsSection(stringResource(R.string.settings_section_car))
    SettingsRow(Icons.Filled.DirectionsCar, stringResource(R.string.car_menu), car.name, onCar)
    SpeedCorrectionRow()
    SettingsRow(Icons.Filled.AutoAwesome, stringResource(R.string.ai_title), stringResource(R.string.settings_ai_detail), onAi)
    SettingsRow(Icons.Filled.Handyman, stringResource(R.string.upkeep_dialog_title), stringResource(R.string.upkeep_settings_detail), onUpkeep)
    SettingsRow(Icons.Filled.Science, stringResource(R.string.explore_title), stringResource(R.string.explore_settings_detail), onExplorer)
}

@Composable
private fun LookPane(theme: ThemeState) {
    ThemePane(theme)
    Spacer(Modifier.height(20.dp))
    SettingsSection(stringResource(R.string.language_title))
    LanguageChoices()
}

@Composable
private fun DrivingPane(m: TopBarModel) {
    val context = LocalContext.current
    SettingsSection(stringResource(R.string.settings_section_driving))
    SettingsToggle(
        Icons.Filled.DirectionsCar, stringResource(R.string.settings_drive_lock),
        stringResource(R.string.settings_drive_lock_detail), m.lockWhileMoving, m.onLockWhileMoving
    )
    SettingsToggle(
        Icons.Filled.VolumeUp, stringResource(R.string.settings_tap_sound),
        stringResource(R.string.settings_tap_sound_detail), FeedbackStore.sound
    ) { FeedbackStore.save(context, it) }
}

@Composable
private fun AdvancedPane(m: TopBarModel, onBootLogo: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_section_advanced))
    // Only on the QF001 / K706 firmware the feature was built for.
    if (BootLogoSupport.available) {
        SettingsRow(Icons.Filled.PowerSettingsNew, stringResource(R.string.boot_menu), null, onBootLogo)
    }
    SettingsRow(Icons.Filled.Build, stringResource(R.string.dash_system_app_title), stringResource(R.string.settings_system_detail), m.onSystem)
    SettingsRow(
        Icons.Filled.SystemUpdate, stringResource(R.string.dash_menu_check_updates),
        stringResource(R.string.settings_version, m.versionName), m.onCheckUpdates
    )
}

@Composable
internal fun SettingsSection(title: String) {
    Text(
        title.uppercase(),
        color = DashColors.Accent,
        letterSpacing = 0.08.em,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)
    )
}

/** One setting: icon, name, what it is right now or what it does, and a chevron. */
@Composable
internal fun SettingsRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(DashShape.Medium)
            .clickable { tap(); onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    color = DashColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = DashColors.Muted)
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/**
 * The speed correction (see [SpeedCorrection]): − and + by 1 km/h, the value
 * between, applied at once so the speed on screen can be matched to the car's
 * speedometer while driving along. Also in the telemetry tile's dialog.
 */
@Composable
internal fun SpeedCorrectionRow() {
    val context = LocalContext.current
    val offset by SpeedCorrection.offsetKmh.collectAsState()
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Speed, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.vehicle_speed_fix), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.vehicle_speed_fix_detail),
                color = DashColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { tap(); SpeedCorrection.save(context, offset - 1) },
            enabled = offset > -SpeedCorrection.MAX_OFFSET_KMH,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.vehicle_speed_fix_less), tint = DashColors.TextPrimary)
        }
        Text(
            speedOffsetText(offset),
            color = if (offset == 0) DashColors.TextSecondary else DashColors.Accent,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(88.dp)
        )
        IconButton(
            onClick = { tap(); SpeedCorrection.save(context, offset + 1) },
            enabled = offset < SpeedCorrection.MAX_OFFSET_KMH,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vehicle_speed_fix_more), tint = DashColors.TextPrimary)
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}

/** "+3 km/h", "−2 km/h", or "0 km/h" when there's no correction. */
internal fun speedOffsetText(offsetKmh: Int): String = when {
    offsetKmh > 0 -> "+$offsetKmh km/h"
    offsetKmh < 0 -> "\u2212${-offsetKmh} km/h"
    else -> "0 km/h"
}

/** One on/off setting: icon, name, what it does, and a switch; the whole row toggles it. */
@Composable
internal fun SettingsToggle(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(DashShape.Medium)
            .clickable(role = Role.Switch) { tap(); onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DashColors.OnAccent,
                checkedTrackColor = DashColors.Accent,
                uncheckedThumbColor = DashColors.TextSecondary,
                uncheckedTrackColor = DashColors.CardHi,
                uncheckedBorderColor = DashColors.Line
            )
        )
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
}
