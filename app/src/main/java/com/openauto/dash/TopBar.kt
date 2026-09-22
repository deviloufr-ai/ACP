@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/*
 * Top bar, status chips, edit toolbar, page dots and the update banner.
 */

@Composable
internal fun TopBar(
    currentPage: Int,
    clock: String,
    versionName: String,
    obdConnection: ObdConnectionState,
    obdData: ObdData,
    editing: Boolean,
    onApps: () -> Unit,
    onMaps: () -> Unit,
    onSplit: () -> Unit,
    onToggleEdit: () -> Unit,
    onTheme: () -> Unit,
    onSystem: () -> Unit
) {
    if (DashColors.Original) {
        OriginalTopBar(
            currentPage, clock, versionName, obdConnection, editing,
            onApps, onMaps, onSplit, onToggleEdit, onTheme, onSystem
        )
        return
    }
    // Glass themes float the bar as its own panel over the gradient background;
    // solid themes keep the flat full-width strip.
    val glass = DashColors.Glass
    Surface(color = if (glass) Color.Transparent else DashColors.Bar, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glass) Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp).then(glassPanel(RoundedCornerShape(20.dp)))
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onApps,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DashColors.CardHi,
                    contentColor = DashColors.TextPrimary
                ),
                border = if (glass) BorderStroke(1.dp, DashColors.Line) else null,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Apps")
            }

            // Brand block: wordmark over the page / version line, like the mockup.
            Column {
                Text(
                    text = "OPENAUTO DASH",
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.2.em,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "Dashboard ${currentPage + 1} · v$versionName",
                    color = DashColors.Muted,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = clock,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.width(4.dp))

            // Live status chips: OBD link, then battery and coolant while connected.
            val connected = obdConnection == ObdConnectionState.CONNECTED
            StatusChip(
                label = "OBD",
                value = when (obdConnection) {
                    ObdConnectionState.CONNECTED -> "Connected"
                    ObdConnectionState.CONNECTING -> "Connecting"
                    ObdConnectionState.ERROR -> "Error"
                    ObdConnectionState.DISCONNECTED -> "Off"
                },
                dot = when (obdConnection) {
                    ObdConnectionState.CONNECTED -> DashColors.Good
                    ObdConnectionState.CONNECTING -> DashColors.Speed
                    ObdConnectionState.ERROR -> DashColors.Warning
                    ObdConnectionState.DISCONNECTED -> DashColors.Muted
                },
                good = connected
            )
            if (connected) {
                StatusChip(
                    label = "Battery",
                    value = "%.1fV".format(obdData.voltage),
                    icon = Icons.Filled.BatteryStd
                )
                StatusChip(
                    label = "Coolant",
                    value = "${obdData.coolantTempC}°C",
                    icon = Icons.Filled.Thermostat
                )
            }
            Spacer(Modifier.width(4.dp))

            IconButton(onClick = onMaps) {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = "Google Maps split-screen",
                    tint = DashColors.TextSecondary
                )
            }

            IconButton(onClick = onSplit) {
                Icon(
                    imageVector = Icons.Filled.Splitscreen,
                    contentDescription = "Split screen with an app",
                    tint = DashColors.TextSecondary
                )
            }

            IconButton(onClick = onSystem) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = "System app",
                    tint = DashColors.TextSecondary
                )
            }

            IconButton(onClick = onTheme) {
                Icon(
                    imageVector = Icons.Filled.Palette,
                    contentDescription = "Dashboard theme",
                    tint = DashColors.TextSecondary
                )
            }

            IconButton(onClick = onToggleEdit) {
                Icon(
                    imageVector = if (editing) Icons.Filled.Done else Icons.Filled.Edit,
                    contentDescription = if (editing) "Done editing" else "Edit dashboards",
                    tint = if (editing) DashColors.Accent else DashColors.TextSecondary
                )
            }
        }
    }
}

/** Top-bar status pill: a coloured dot or icon, a muted label and a bold value. */
@Composable
internal fun StatusChip(
    label: String,
    value: String,
    dot: Color? = null,
    icon: ImageVector? = null,
    good: Boolean = false
) {
    val shape = RoundedCornerShape(999.dp)
    val fill: Brush = if (good) {
        Brush.horizontalGradient(listOf(DashColors.Good.copy(alpha = 0.20f), DashColors.Accent.copy(alpha = 0.12f)))
    } else if (DashColors.Glass) {
        SolidColor(Color.White.copy(alpha = 0.05f))
    } else SolidColor(DashColors.CardHi)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, if (good) DashColors.Good.copy(alpha = 0.35f) else DashColors.Line, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .drawBehind {
                        if (good) drawCircle(color = dot.copy(alpha = 0.45f), radius = size.minDimension)
                    }
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (good) DashColors.Good else DashColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
        Spacer(Modifier.width(5.dp))
        Text(
            value,
            color = if (good) DashColors.Good else DashColors.TextPrimary,
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
                "Arranging dashboard ${page + 1}",
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "Long-press a tile to move it, drag the corner handle to resize. Dropping on a tile swaps or nudges it.",
                color = DashColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add", color = DashColors.TextPrimary)
        }
        TextButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                Icons.Filled.Undo, contentDescription = null,
                tint = if (canUndo) DashColors.TextPrimary else DashColors.Muted, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("Undo", color = if (canUndo) DashColors.TextPrimary else DashColors.Muted)
        }
        TextButton(onClick = onReset) {
            Text("Reset page", color = DashColors.Warning)
        }
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Done")
        }
    }
}

@Composable
internal fun PageDots(count: Int, current: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (index == current) 22.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (index == current) DashColors.AccentBrush else SolidColor(DashColors.CardHi))
                    .clickable { onSelect(index) }
            )
        }
    }
}

@Composable
internal fun UpdateBanner(
    status: UpdateStatus,
    onUpdate: (UpdateInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val visible = status is UpdateStatus.Available ||
        status is UpdateStatus.Downloading ||
        status is UpdateStatus.Installing
    if (!visible) return

    Surface(
        color = DashColors.Accent,
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
                        is UpdateStatus.Available -> "Update available — ${status.info.versionName}"
                        is UpdateStatus.Downloading -> "Downloading update… ${status.percent}%"
                        is UpdateStatus.Installing -> "Starting installer…"
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
                        Text("Update")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = DashColors.Background
                        )
                    }
                }

                is UpdateStatus.Downloading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = DashColors.Background,
                    strokeWidth = 2.dp
                )

                else -> {}
            }
        }
    }
}
