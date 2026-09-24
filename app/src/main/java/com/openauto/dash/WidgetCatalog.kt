package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The "Add widget" catalogue: every built-in tile grouped by category, plus
 * the launch bar and hosted system widgets.
 */

/** Display name of a built-in tile, in the current language. */
val BuiltinKind.label: String
    @Composable get() = stringResource(labelRes)

/** One-line description of a built-in tile for the picker, in the current language. */
val BuiltinKind.blurb: String
    @Composable get() = stringResource(blurbRes)

internal fun kindIcon(kind: BuiltinKind): ImageVector = when (kind) {
    BuiltinKind.NAVMAP -> Icons.Filled.Navigation
    BuiltinKind.NAVIGATION -> Icons.Filled.Directions
    BuiltinKind.PIP_ANCHOR -> Icons.Filled.PictureInPicture
    BuiltinKind.MEDIA -> Icons.Filled.MusicNote
    BuiltinKind.TELEMETRY -> Icons.Filled.Speed
    BuiltinKind.OBD_DTC -> Icons.Filled.Warning
    BuiltinKind.OBD_ALL -> Icons.Filled.Sensors
    BuiltinKind.RANGE -> Icons.Filled.LocalGasStation
    BuiltinKind.CAR3D -> Icons.Filled.DirectionsCar
    BuiltinKind.DOORS -> Icons.Filled.SensorDoor
    BuiltinKind.CAN_MON -> Icons.Filled.Sensors
    BuiltinKind.SPEED_HUD -> Icons.Filled.Speed
    BuiltinKind.COMPASS -> Icons.Filled.Explore
    BuiltinKind.TRIP -> Icons.Filled.Timeline
    BuiltinKind.GFORCE -> Icons.Filled.Adjust
    BuiltinKind.PARKING -> Icons.Filled.LocalParking
    BuiltinKind.CLOCK -> Icons.Filled.Schedule
    BuiltinKind.WEATHER -> Icons.Filled.WbSunny
    BuiltinKind.CALENDAR -> Icons.Filled.Event
    BuiltinKind.QUICK_DIAL -> Icons.Filled.Call
    BuiltinKind.NOTIFICATIONS -> Icons.Filled.Notifications
    BuiltinKind.AUDIO -> Icons.Filled.VolumeUp
    BuiltinKind.FILTER_CARE -> Icons.Filled.FilterAlt
    BuiltinKind.WARMUP -> Icons.Filled.Thermostat
    BuiltinKind.BATTERY -> Icons.Filled.BatteryChargingFull
    BuiltinKind.MY_CAR -> Icons.Filled.CarRepair
    BuiltinKind.ECO_DRIVE -> Icons.Filled.Eco
    BuiltinKind.BREAK_TIMER -> Icons.Filled.Coffee
    BuiltinKind.FUEL_TO_DEST -> Icons.Filled.EvStation
}

/** Picker for a new tile: built-ins by category, then the launch bar and system widgets. */
@Composable
internal fun WidgetPickerDialog(
    onPickBuiltin: (BuiltinKind) -> Unit,
    onPickLaunchBar: () -> Unit,
    onPickSystemWidget: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card.copy(alpha = 1f),
        title = { Text(stringResource(R.string.apps_add_widget), color = DashColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WidgetCategory.entries.forEach { category ->
                    val kinds = BuiltinKind.entries.filter { it.category == category }
                    if (kinds.isEmpty() && category != WidgetCategory.APPS) return@forEach
                    Text(
                        stringResource(category.titleRes),
                        color = DashColors.Muted,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                    kinds.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { kind ->
                                ChoiceCell(kindIcon(kind), kind.label, kind.blurb, Modifier.weight(1f)) { onPickBuiltin(kind) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (category == WidgetCategory.APPS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoiceCell(Icons.Filled.Apps, stringResource(R.string.apps_pick_launch_bar), stringResource(R.string.apps_pick_launch_bar_blurb), Modifier.weight(1f), onPickLaunchBar)
                            ChoiceCell(Icons.Filled.Widgets, stringResource(R.string.apps_pick_system_widget), stringResource(R.string.apps_pick_system_widget_blurb), Modifier.weight(1f), onPickSystemWidget)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_cancel), color = DashColors.Muted) }
        }
    )
}

@Composable
private fun ChoiceCell(icon: ImageVector, label: String, blurb: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(DashColors.CardHi)
            .border(1.dp, DashColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(blurb, color = DashColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}
