package com.openauto.dash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/*
 * A tile's options while arranging (a tap on the tile): its text size, its
 * design, the page to move it to, and removing it. What used to be four
 * buttons on every tile is one sheet for the tile that was tapped.
 */

@Composable
internal fun TileOptionsDialog(
    item: DashboardItem,
    page: Int,
    onZoom: (Float) -> Unit,
    /** Null when the tile has no designs (an app shortcut, a window, a system widget). */
    onDesign: (() -> Unit)?,
    onMoveTo: (Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var pickingPage by remember { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(item.describe(), color = DashColors.TextPrimary) },
        text = {
            Column {
                if (item.canZoom()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Filled.FormatSize, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.zoom_button), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        ZoomStepper(item.zoom, onZoom)
                    }
                    HorizontalDivider(color = DashColors.Line)
                }
                if (onDesign != null) {
                    OptionRow(Icons.Filled.Palette, stringResource(R.string.dash_tile_design), onClick = onDesign)
                }
                OptionRow(
                    icon = Icons.Filled.SwapHoriz,
                    title = stringResource(R.string.dash_tile_move),
                    trailing = if (pickingPage) Icons.Filled.ExpandLess else Icons.Filled.ChevronRight,
                    onClick = { pickingPage = !pickingPage }
                )
                if (pickingPage) {
                    Column(modifier = Modifier.padding(start = 38.dp)) {
                        (0 until DashboardStore.PAGE_COUNT).filter { it != page }.forEach { target ->
                            OptionRow(icon = null, title = stringResource(DashboardStore.nameRes(target)), divider = false) { onMoveTo(target) }
                        }
                    }
                    HorizontalDivider(color = DashColors.Line)
                }
                OptionRow(Icons.Filled.Delete, stringResource(R.string.dash_tile_remove), tint = DashColors.Critical, divider = false, onClick = onRemove)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_done), color = DashColors.Accent) }
        }
    )
}

@Composable
private fun OptionRow(
    icon: ImageVector?,
    title: String,
    tint: Color = DashColors.TextPrimary,
    trailing: ImageVector? = null,
    divider: Boolean = true,
    onClick: () -> Unit
) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(DashShape.Small)
            .clickable(role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (tint == DashColors.TextPrimary) DashColors.TextSecondary else tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(title, color = tint, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (trailing != null) Icon(trailing, contentDescription = null, tint = DashColors.Muted)
    }
    if (divider) HorizontalDivider(color = DashColors.Line)
}
