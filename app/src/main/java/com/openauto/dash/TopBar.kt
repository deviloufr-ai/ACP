@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/*
 * Top bar, edit toolbar, page dots and the update banner.
 */

/** Everything a top bar shows and can do; each skin's bar arranges the same model. */
internal class TopBarModel(
    val clock: String,
    val versionName: String,
    val obdConnection: ObdConnectionState,
    val obdData: ObdData,
    val editing: Boolean,
    val layout: DashLayout,
    val onLayout: (DashLayout) -> Unit,
    val onApps: () -> Unit,
    val onConnectObd: () -> Unit,
    val onSplit: () -> Unit,
    val onToggleEdit: () -> Unit,
    val onTheme: () -> Unit,
    val onAi: () -> Unit,
    val onSystem: () -> Unit,
    val onLanguage: () -> Unit,
    val onCheckUpdates: () -> Unit,
    /**
     * The head unit's status bar is up and already shows the time: the bar puts
     * the page dots ([BarPageDots]) where its clock was, so no row of dots is
     * needed under it.
     */
    val merged: Boolean = false,
    val page: Int = 0,
    val onPage: (Int) -> Unit = {}
)

@Composable
internal fun TopBar(
    clock: String,
    versionName: String,
    obdConnection: ObdConnectionState,
    obdData: ObdData,
    editing: Boolean,
    layout: DashLayout,
    onLayout: (DashLayout) -> Unit,
    onApps: () -> Unit,
    onConnectObd: () -> Unit,
    onSplit: () -> Unit,
    onToggleEdit: () -> Unit,
    onTheme: () -> Unit,
    onAi: () -> Unit,
    onSystem: () -> Unit,
    onLanguage: () -> Unit,
    onCheckUpdates: () -> Unit,
    merged: Boolean = false,
    page: Int = 0,
    onPage: (Int) -> Unit = {}
) {
    val m = TopBarModel(
        clock, versionName, obdConnection, obdData, editing, layout, onLayout,
        onApps, onConnectObd, onSplit, onToggleEdit, onTheme, onAi, onSystem,
        onLanguage, onCheckUpdates, merged, page, onPage
    )
    if (DashColors.Skin == DashSkin.STANDARD) StandardTopBar(m) else SkinTopBar(m)
}

/**
 * Minimal bar: Apps and the layout picker on the left, the clock centred, the
 * OBD link dot and a ⋮ menu on the right. Set-and-forget controls (theme,
 * system install, edit) live in the menu; battery and coolant only appear, as
 * warning pills, when a reading is out of range.
 */
@Composable
internal fun StandardTopBar(m: TopBarModel) {
    // Glass themes float the bar as its own panel over the gradient background;
    // solid themes keep the flat full-width strip.
    val glass = DashColors.Glass
    Surface(color = if (glass) Color.Transparent else DashColors.Bar, modifier = Modifier.fillMaxWidth()) {
        // A Box, not a Row, so the clock sits at the true centre whatever the
        // two sides hold.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glass) Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).then(glassPanel(RoundedCornerShape(20.dp)))
                    else Modifier
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(modifier = Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = m.onApps) {
                    Icon(Icons.Filled.Apps, contentDescription = stringResource(R.string.dash_all_apps), tint = DashColors.TextPrimary)
                }
                LayoutPicker(m) { open ->
                    IconButton(onClick = open) {
                        LayoutIcon(m.layout, stringResource(R.string.dash_screen_layout, m.layout.title), DashColors.TextSecondary)
                    }
                }
            }

            if (m.merged) {
                BarPageDots(m)
            } else {
                Text(
                    text = m.clock,
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.02).em,
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                VehicleAlerts(m.obdConnection, m.obdData)
                ObdDot(m.obdConnection, m.onConnectObd)
                MorePicker(m) { open ->
                    IconButton(onClick = open) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.dash_more), tint = DashColors.TextSecondary)
                    }
                }
            }
        }
    }
}

/**
 * Layout picker around any [anchor] a skin draws: the anchor gets an `open`
 * callback, the menu offers all three layouts with the current one checked.
 */
@Composable
internal fun LayoutPicker(m: TopBarModel, anchor: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        anchor { open = true }
        DashMenu(open, onDismiss = { open = false }) {
            DashLayout.entries.forEach { l ->
                DashMenuItem(
                    text = l.title,
                    leading = { LayoutIcon(l, null, if (l == m.layout) DashColors.Accent else DashColors.TextSecondary) },
                    selected = l == m.layout,
                    onClick = {
                        open = false
                        m.onLayout(l)
                    }
                )
            }
        }
    }
}

/** Icon for a layout: a dashboard, or a split whose solid pane (the map) sits on the docked side. */
@Composable
internal fun LayoutIcon(layout: DashLayout, contentDescription: String?, tint: Color, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (layout == DashLayout.GRID) Icons.Filled.SpaceDashboard else Icons.Filled.VerticalSplit,
        contentDescription = contentDescription,
        tint = tint,
        // VerticalSplit draws its solid pane (the map) on the right; mirror it for the left dock.
        modifier = modifier.then(if (layout == DashLayout.MAPS_LEFT) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier)
    )
}

internal fun obdStatusColor(state: ObdConnectionState): Color = when (state) {
    ObdConnectionState.CONNECTED -> DashColors.Good
    ObdConnectionState.CONNECTING -> DashColors.Speed
    ObdConnectionState.ERROR -> DashColors.Warning
    ObdConnectionState.DISCONNECTED -> DashColors.Muted
}

@StringRes
internal fun obdStatusLabelRes(state: ObdConnectionState): Int = when (state) {
    ObdConnectionState.CONNECTED -> R.string.dash_obd_connected
    ObdConnectionState.CONNECTING -> R.string.dash_obd_connecting
    ObdConnectionState.ERROR -> R.string.dash_obd_error
    ObdConnectionState.DISCONNECTED -> R.string.dash_obd_off
}

/** Spoken OBD link state; outside composition use [obdStatusLabelRes]. */
@Composable
internal fun obdStatusLabel(state: ObdConnectionState): String = stringResource(obdStatusLabelRes(state))

/** OBD link as a coloured dot; tapping it while disconnected connects. */
@Composable
internal fun ObdDot(state: ObdConnectionState, onConnect: () -> Unit, dotSize: Dp = 10.dp) {
    val color = obdStatusColor(state)
    val label = obdStatusLabel(state)
    val idle = state.isIdle
    IconButton(
        onClick = onConnect,
        enabled = idle,
        modifier = Modifier.semantics { contentDescription = label }
    ) {
        Box(
            Modifier
                .size(dotSize)
                .drawBehind {
                    if (state == ObdConnectionState.CONNECTED) {
                        drawCircle(color = color.copy(alpha = 0.45f), radius = size.minDimension)
                    }
                }
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** Menu around any [anchor] a skin draws: edit, theme, AI mechanic, split screen, system install, and the version. */
@Composable
internal fun MorePicker(m: TopBarModel, anchor: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var carSettings by remember { mutableStateOf(false) }
    var bootLogo by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> () -> Unit = { action ->
        {
            open = false
            action()
        }
    }
    Box {
        anchor { open = true }
        DashMenu(open, onDismiss = { open = false }) {
            DashMenuItem(
                text = stringResource(if (m.editing) R.string.dash_menu_done_editing else R.string.dash_menu_edit_dashboards),
                leading = { MenuIcon(if (m.editing) Icons.Filled.Done else Icons.Filled.Edit) },
                onClick = pick(m.onToggleEdit)
            )
            DashMenuItem(stringResource(R.string.dash_menu_theme), leading = { MenuIcon(Icons.Filled.Palette) }, onClick = pick(m.onTheme))
            DashMenuItem(stringResource(R.string.language_menu), leading = { MenuIcon(Icons.Filled.Language) }, onClick = pick(m.onLanguage))
            DashMenuItem(stringResource(R.string.ai_title), leading = { MenuIcon(Icons.Filled.AutoAwesome) }, onClick = pick(m.onAi))
            DashMenuItem(stringResource(R.string.car_menu), leading = { MenuIcon(Icons.Filled.DirectionsCar) }, onClick = pick { carSettings = true })
            // Only on the QF001 / K706 firmware the feature was built for.
            if (BootLogoSupport.available) {
                DashMenuItem(stringResource(R.string.boot_menu), leading = { MenuIcon(Icons.Filled.PowerSettingsNew) }, onClick = pick { bootLogo = true })
            }
            DashMenuItem(stringResource(R.string.dash_menu_split_screen), leading = { MenuIcon(Icons.Filled.Splitscreen) }, onClick = pick(m.onSplit))
            DashMenuItem(stringResource(R.string.dash_system_app_title), leading = { MenuIcon(Icons.Filled.Build) }, onClick = pick(m.onSystem))
            DashMenuItem(stringResource(R.string.dash_menu_check_updates), leading = { MenuIcon(Icons.Filled.SystemUpdate) }, onClick = pick(m.onCheckUpdates))
            HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "Dashwheel v${m.versionName}",
                color = DashColors.Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
    if (carSettings) CarSettingsDialog(onDismiss = { carSettings = false })
    if (bootLogo) BootLogoDialog(onDismiss = { bootLogo = false })
}

@Composable
private fun DashMenu(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // Docked windows are drawn above the bar's pop-ups; one the menu overlaps steps aside meanwhile.
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        modifier = Modifier.keepClearOfWindows(),
        shape = RoundedCornerShape(16.dp),
        containerColor = DashColors.Card.copy(alpha = 1f),
        border = BorderStroke(1.dp, DashColors.Line)
    ) {
        content()
    }
}

@Composable
private fun DashMenuItem(
    text: String,
    leading: @Composable () -> Unit,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                color = if (selected) DashColors.Accent else DashColors.TextPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        leadingIcon = leading,
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent) }
        } else null,
        onClick = onClick
    )
}

@Composable
private fun MenuIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = DashColors.TextSecondary)
}

/**
 * Warning pills for out-of-range readings (battery outside 12–15 V, coolant at
 * 105 °C or more); emits nothing while everything is normal or OBD is off.
 */
@Composable
internal fun VehicleAlerts(obdConnection: ObdConnectionState, obdData: ObdData) {
    if (obdConnection != ObdConnectionState.CONNECTED) return
    val volts = obdData.voltage
    // 0.0 is "no reading yet", not a flat battery.
    if (volts > 0.0 && volts !in 12.0..15.0) {
        AlertChip(Icons.Filled.BatteryAlert, stringResource(R.string.dash_alert_battery, volts))
    }
    if (obdData.coolantTempC >= 105) {
        AlertChip(Icons.Filled.Thermostat, stringResource(R.string.dash_alert_coolant, obdData.coolantTempC))
    }
}

/** Warning pill for an out-of-range reading; the bar shows these only when something needs attention. */
@Composable
private fun AlertChip(icon: ImageVector, text: String) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(shape)
            .background(DashColors.Warning.copy(alpha = 0.14f))
            .border(1.dp, DashColors.Warning.copy(alpha = 0.45f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = DashColors.Warning,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}


/**
 * Toolbar shown while arranging: what to do, plus Add / Undo / Reset / Done.
 * Changes save as they happen; Undo walks back through the last edits.
 */
@Composable
internal fun EditBar(
    page: Int,
    canUndo: Boolean,
    onAdd: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onTemplates: () -> Unit,
    onDone: () -> Unit
) {
    val glass = DashColors.Glass
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .then(if (glass) glassPanel(shape) else Modifier.clip(shape).background(DashColors.Bar))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(DashColors.Accent)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.dash_arranging_dashboard, page + 1),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                stringResource(R.string.dash_arranging_hint),
                color = DashColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.dash_add), color = DashColors.TextPrimary)
        }
        TextButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                Icons.Filled.Undo, contentDescription = null,
                tint = if (canUndo) DashColors.TextPrimary else DashColors.Muted, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.dash_undo), color = if (canUndo) DashColors.TextPrimary else DashColors.Muted)
        }
        TextButton(onClick = onTemplates) {
            Icon(Icons.Filled.Dashboard, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.templates_button), color = DashColors.TextPrimary)
        }
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.dash_reset_page), color = DashColors.Warning)
        }
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.dash_done))
        }
    }
}

@Composable
internal fun PageDots(current: Int, onSelect: (Int) -> Unit) {
    PageDotRow(current, onSelect, Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

/** The page dots inside a bar, in the clock's place while the head unit's status bar shows the time. */
@Composable
internal fun BarPageDots(m: TopBarModel) {
    PageDotRow(m.page, m.onPage, Modifier)
}

/**
 * The dashboards drawn as the cross they form: the row of pages swiped
 * sideways, with the middle dot's column of smaller dots for the pages above
 * and below it.
 */
@Composable
private fun PageDotRow(current: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DashboardStore.ROW.forEach { page ->
            if (page == DashboardStore.CENTER) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DashboardStore.COLUMN.forEach { p ->
                        if (p == DashboardStore.CENTER) {
                            PageDot(p == current, width = 22.dp, height = 8.dp, PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { onSelect(p) }
                        } else {
                            PageDot(p == current, width = 5.dp, height = 11.dp, PaddingValues(horizontal = 14.dp, vertical = 1.dp)) { onSelect(p) }
                        }
                    }
                }
            } else {
                PageDot(page == current, width = 22.dp, height = 8.dp, PaddingValues(horizontal = 6.dp, vertical = 12.dp)) { onSelect(page) }
            }
        }
    }
}

/**
 * One page dot: [width] x [height] is its stretched "you are here" shape, the
 * smaller side its round one. [touch] widens the tap area around the dot.
 */
@Composable
private fun PageDot(selected: Boolean, width: Dp, height: Dp, touch: PaddingValues, onClick: () -> Unit) {
    val round = minOf(width, height)
    Box(
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(touch)
            .size(width = if (selected) width else round, height = if (selected) height else round)
            .clip(CircleShape)
            .background(if (selected) DashColors.AccentBrush else SolidColor(DashColors.CardHi))
    )
}

/**
 * The update strip above the bottom bar. By itself it only appears for an
 * update (available, downloading, installing); after a check asked for from
 * the menu ([showCheck]) it also says checking, up to date, or that it failed.
 */
@Composable
internal fun UpdateBanner(
    status: UpdateStatus,
    currentVersion: String,
    showCheck: Boolean,
    onUpdate: (UpdateInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val checkOutcome = status is UpdateStatus.Checking || status is UpdateStatus.UpToDate || status is UpdateStatus.Error
    val visible = status is UpdateStatus.Available ||
        status is UpdateStatus.Downloading ||
        status is UpdateStatus.Installing ||
        (showCheck && checkOutcome)
    if (!visible) return

    Surface(
        color = if (status is UpdateStatus.Error) DashColors.Warning else DashColors.Accent,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = DashColors.Background,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when (status) {
                        is UpdateStatus.Available -> stringResource(R.string.dash_update_available, status.info.versionName)
                        is UpdateStatus.Downloading -> stringResource(R.string.dash_update_downloading, status.percent)
                        is UpdateStatus.Installing -> stringResource(R.string.dash_update_installing)
                        is UpdateStatus.Checking -> stringResource(R.string.dash_update_checking)
                        is UpdateStatus.UpToDate -> stringResource(R.string.dash_update_up_to_date, currentVersion)
                        is UpdateStatus.Error -> stringResource(status.messageRes)
                        else -> ""
                    },
                    color = DashColors.Background,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            when (status) {
                is UpdateStatus.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onUpdate(status.info) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DashColors.Background,
                            contentColor = DashColors.Accent
                        )
                    ) {
                        Text(stringResource(R.string.dash_update))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.dash_dismiss),
                            tint = DashColors.Background
                        )
                    }
                }

                is UpdateStatus.Downloading, is UpdateStatus.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = DashColors.Background,
                    strokeWidth = 2.dp
                )

                is UpdateStatus.UpToDate, is UpdateStatus.Error -> IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.dash_dismiss),
                        tint = DashColors.Background
                    )
                }

                else -> {}
            }
        }
    }
}
