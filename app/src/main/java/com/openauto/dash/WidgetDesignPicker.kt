package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * Tile designs: routing a built-in tile to its design, and the picker that
 * changes it (edit mode → palette button on the tile).
 */

/**
 * A built-in tile drawn in its non-standard design. Live views (map, Maps
 * window, 3D car) keep their [standard] content inside the design's frame;
 * every other widget draws its live face, or its [standard] tile while it has
 * no reading to show.
 */
@Composable
internal fun DesignedTile(item: DashboardItem.BuiltinWidget, env: SkinTileEnv, standard: @Composable () -> Unit) {
    if (item.kind in FRAMED_KINDS) {
        DesignFrame(item.design, kindIcon(item.kind), item.kind.label, Modifier.fillMaxSize()) { standard() }
        return
    }
    val face = rememberWidgetFace(item.kind, env)
    if (face == null) standard() else DesignedFace(face, item.design, Modifier.fillMaxSize())
}

/**
 * Every design for [kind], each previewed with the widget's live reading (or a
 * stand-in while it has none) at the tile's own proportions [aspect].
 * [standardPreview] draws the tile as it is today.
 */
@Composable
internal fun WidgetDesignPickerDialog(
    kind: BuiltinKind,
    current: WidgetDesign,
    aspect: Float,
    env: SkinTileEnv,
    standardPreview: @Composable () -> Unit,
    onPick: (WidgetDesign) -> Unit,
    onDismiss: () -> Unit
) {
    val live = if (kind in FRAMED_KINDS) null else rememberWidgetFace(kind, env)
    val face = live ?: sampleFace(kind)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .keepClearOfWindows()
                .fillMaxWidth(0.94f)
                .widthIn(max = 1040.dp),
            shape = RoundedCornerShape(28.dp),
            color = DashColors.Card.copy(alpha = 1f)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.design_picker_title, kind.label),
                    color = DashColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    stringResource(if (live == null && kind !in FRAMED_KINDS) R.string.design_picker_hint_sample else R.string.design_picker_hint),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(WidgetDesign.entries, key = { it.name }) { design ->
                        DesignChoice(design, design == current, aspect, onClick = { onPick(design) }) {
                            when {
                                design == WidgetDesign.STANDARD -> standardPreview()
                                kind in FRAMED_KINDS -> DesignFrame(design, kindIcon(kind), kind.label, Modifier.fillMaxSize()) { FramedPlaceholder(kind) }
                                else -> DesignedFace(face, design, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_cancel), color = DashColors.Muted) }
                }
            }
        }
    }
}

@Composable
private fun DesignChoice(
    design: WidgetDesign,
    selected: Boolean,
    aspect: Float,
    onClick: () -> Unit,
    preview: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.6f else 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.8f, 3f))
                .clip(RoundedCornerShape(12.dp))
                .background(dashBackgroundBrush())
                .padding(4.dp)
        ) {
            preview()
            // Taps pick the design; they never reach the preview's own buttons.
            Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = design.title, onClick = onClick))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(design.title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent, modifier = Modifier.size(18.dp))
        }
        Text(
            design.description, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp).height(32.dp)
        )
    }
}

/** The page colour behind a preview, so light and bare designs show as they would on the dashboard. */
private fun dashBackgroundBrush(): Brush = Brush.linearGradient(DashColors.BackgroundStops)

/** What a framed live view stands in for inside the picker (the real map or model is too heavy to run sixteen times). */
@Composable
private fun FramedPlaceholder(kind: BuiltinKind) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(DashColors.CardHi, DashColors.Card))),
        contentAlignment = Alignment.Center
    ) {
        Icon(kindIcon(kind), contentDescription = null, tint = DashColors.Accent.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
    }
}
