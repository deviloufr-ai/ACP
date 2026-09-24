@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import kotlin.math.ceil
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The free-placement tile grid: page layout, drag / resize tiles, tile content routing.
 */

/** Natural height of a full widget tile stacked in the scrollable split-screen column. */
internal val SPLIT_WIDGET_HEIGHT = 300.dp

internal data class GridPreview(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val isValid: Boolean
)

@Composable
internal fun DashboardPage(
    pageItems: List<DashboardItem>,
    editing: Boolean,
    inSplitMode: Boolean,
    onModelTouch: (Boolean) -> Unit,
    appsByPackage: Map<String, AppEntry>,
    mediaState: MediaState,
    mediaController: CarMediaController,
    hasMediaAccess: Boolean,
    obdData: ObdData,
    obdConnection: ObdConnectionState,
    onConnectObd: () -> Unit,
    onPickDevice: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onLaunchSplitPair: (String, String) -> Unit,
    onEditLaunchBar: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMoveCell: (Int, Int, Int) -> Unit,
    onResizeCell: (Int, Int, Int) -> Unit,
    canPlace: (Int, Int, Int, Int, Int) -> Boolean,
    canMove: (Int, Int, Int) -> Boolean,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Renders one tile's inner content with all the shared dependencies wired in.
    val tileContent: @Composable (Int, DashboardItem, ((Int, Int) -> Unit)?) -> Unit = { index, item, fit ->
        TileContent(
            item = item,
            editing = editing && !inSplitMode,
            appsByPackage = appsByPackage,
            mediaState = mediaState,
            mediaController = mediaController,
            hasMediaAccess = hasMediaAccess,
            context = context,
            obdData = obdData,
            obdConnection = obdConnection,
            onConnectObd = onConnectObd,
            onPickDevice = onPickDevice,
            onLaunchApp = onLaunchApp,
            onLaunchSplitPair = onLaunchSplitPair,
            onEditLaunchBar = { onEditLaunchBar(index) },
            onModelTouch = onModelTouch,
            onFitToWindow = fit
        )
    }

    if (inSplitMode) {
        // Sharing the screen: the pane is narrow and tall, so ignore the grid and
        // stack tiles vertically at a natural height, scrolling when they overflow.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val keys = remember(pageItems) { tileKeys(pageItems) }
            pageItems.forEachIndexed { index, item ->
                key(keys[index]) {
                    val h = when {
                        item.isCompactTile() -> 96.dp
                        item is DashboardItem.LaunchBar -> 88.dp
                        else -> SPLIT_WIDGET_HEIGHT
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(h)
                            .clip(RoundedCornerShape(20.dp))
                            .background(DashColors.Bar)
                    ) { tileContent(index, item, null) }
                }
            }
        }
        return
    }

    // Free placement on a GRID_COLS x GRID_ROWS grid: each tile sits at its own
    // cell rectangle and can be dragged to any cell and resized by its handle.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        val cellW = maxWidth / GRID_COLS
        val cellH = maxHeight / GRID_ROWS
        val cellWpx = with(density) { cellW.toPx() }
        val cellHpx = with(density) { cellH.toPx() }

        // While a tile is dragged / resized, [preview] holds the cell rectangle
        // (x, y, w, h) it will snap to, drawn as a highlighted ghost.
        var preview by remember { mutableStateOf<GridPreview?>(null) }
        // Safety net: never leave the ghost stranded once a move/resize commits
        // (pageItems changes) or edit mode is toggled.
        LaunchedEffect(pageItems, editing) { preview = null }

        if (editing) {
            // Grid guide-lines while arranging.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val line = DashColors.TextSecondary.copy(alpha = 0.28f)
                for (c in 0..GRID_COLS) {
                    val x = (cellWpx * c).coerceAtMost(size.width - 0.5f)
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 2f)
                }
                for (r in 0..GRID_ROWS) {
                    val y = (cellHpx * r).coerceAtMost(size.height - 0.5f)
                    drawLine(line, Offset(0f, y), Offset(size.width, y), 2f)
                }
            }

            // Snap-target ghost, above the resting tiles but below the dragged one.
            preview?.let { p ->
                val previewColor = if (p.isValid) DashColors.Accent else DashColors.Warning
                Box(
                    modifier = Modifier
                        .zIndex(0.5f)
                        .offset(cellW * p.x, cellH * p.y)
                        .size(cellW * p.w, cellH * p.h)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(previewColor.copy(alpha = 0.22f))
                        .border(2.dp, previewColor, RoundedCornerShape(18.dp))
                )
            }
        }

        val keys = remember(pageItems) { tileKeys(pageItems) }
        pageItems.forEachIndexed { index, item ->
            // Keyed by what the tile *is*, not its list position, so removing or
            // reordering another tile never re-creates this one (which would
            // rebuild a hosted map / widget view) or leaves it with stale state.
            key(keys[index]) {
                GridTile(
                    index = index,
                    item = item,
                    cellW = cellW,
                    cellH = cellH,
                    cellWpx = cellWpx,
                    cellHpx = cellHpx,
                    editing = editing,
                    onModelTouch = onModelTouch,
                    onMoveCell = onMoveCell,
                    onResizeCell = onResizeCell,
                    canPlace = canPlace,
                    canMove = canMove,
                    onRemove = onRemove,
                    onPreview = { x, y, w, h, isValid -> preview = GridPreview(x, y, w, h, isValid) },
                    onPreviewClear = { preview = null },
                    content = {
                        val cellWpxF = with(density) { cellW.toPx() }
                        val cellHpxF = with(density) { cellH.toPx() }
                        // The tile's inner padding eats a few dp of every span.
                        val padPx = with(density) { 12.dp.toPx() }
                        tileContent(index, item) { wPx, hPx ->
                            val cw = ceil((wPx + padPx) / cellWpxF).toInt().coerceIn(item.w, GRID_COLS - item.x)
                            val ch = ceil((hPx + padPx) / cellHpxF).toInt().coerceIn(item.h, GRID_ROWS - item.y)
                            // Grow silently, and only when nothing is in the way: a
                            // notice here would loop, since dismissing it re-measures.
                            if ((cw > item.w || ch > item.h) && canPlace(index, item.x, item.y, cw, ch)) onResizeCell(index, cw, ch)
                        }
                    }
                )
            }
        }

        // "+" to add a tile only on an empty page; while arranging, the edit bar
        // has Add, so the button never sits on top of a tile's corner.
        if (pageItems.isEmpty()) {
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                AddTile(onClick = onAdd)
            }
        }
    }
}

/**
 * One tile placed on the dashboard grid. Fixed at its cell rectangle normally;
 * in edit mode it can be long-press-dragged to another cell (snapping on drop),
 * resized by the bottom-right handle, or removed.
 */
@Composable
internal fun GridTile(
    index: Int,
    item: DashboardItem,
    cellW: Dp,
    cellH: Dp,
    cellWpx: Float,
    cellHpx: Float,
    editing: Boolean,
    onModelTouch: (Boolean) -> Unit,
    onMoveCell: (Int, Int, Int) -> Unit,
    onResizeCell: (Int, Int, Int) -> Unit,
    canPlace: (Int, Int, Int, Int, Int) -> Boolean,
    canMove: (Int, Int, Int) -> Boolean,
    onRemove: (Int) -> Unit,
    onPreview: (Int, Int, Int, Int, Boolean) -> Unit,
    onPreviewClear: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var resizeExtra by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }

    val basePxX = with(density) { (cellW * item.x).toPx() }
    val basePxY = with(density) { (cellH * item.y).toPx() }
    val basePxW = with(density) { (cellW * item.w).toPx() }
    val basePxH = with(density) { (cellH * item.h).toPx() }

    // Snapped cell the tile will land on — kept identical to moveCell / resizeCell
    // so the highlighted ghost matches the committed result exactly.
    fun snapX() = ((basePxX + dragOffset.x) / cellWpx).roundToInt().coerceIn(0, GRID_COLS - item.w)
    fun snapY() = ((basePxY + dragOffset.y) / cellHpx).roundToInt().coerceIn(0, GRID_ROWS - item.h)
    fun snapW() = ((basePxW + resizeExtra.x) / cellWpx).roundToInt().coerceIn(item.minW(), GRID_COLS - item.x)
    fun snapH() = ((basePxH + resizeExtra.y) / cellHpx).roundToInt().coerceIn(item.minH(), GRID_ROWS - item.y)

    val offsetX = with(density) { (basePxX + dragOffset.x).toDp() }
    val offsetY = with(density) { (basePxY + dragOffset.y).toDp() }
    val widthDp = with(density) { (basePxW + resizeExtra.x).coerceAtLeast(cellWpx).toDp() }
    val heightDp = with(density) { (basePxH + resizeExtra.y).coerceAtLeast(cellHpx).toDp() }

    Box(
        modifier = Modifier
            .offset(offsetX, offsetY)
            .size(widthDp, heightDp)
            .zIndex(if (active) 1f else 0f)
            .padding(3.dp)
            .graphicsLayer {
                if (active) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 20f }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Bare themes draw no card, so outline each tile while arranging.
                .then(
                    if (editing && DashColors.Bare) Modifier.border(1.dp, DashColors.TextSecondary.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                    else Modifier
                )
        ) { content() }

        if (editing) {
            // Transparent scrim over the content captures the long-press drag so
            // even map / widget tiles (whose content eats touches) can be moved,
            // and taps don't reach the content. The buttons below sit above it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(index, item.x, item.y, item.w, item.h, cellWpx, cellHpx) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                active = true; onModelTouch(true)
                                onPreview(snapX(), snapY(), item.w, item.h, canMove(index, snapX(), snapY()))
                            },
                            onDrag = { change, delta ->
                                change.consume(); dragOffset += delta
                                onPreview(snapX(), snapY(), item.w, item.h, canMove(index, snapX(), snapY()))
                            },
                            onDragEnd = {
                                onMoveCell(index, snapX(), snapY())
                                dragOffset = Offset.Zero; active = false; onModelTouch(false); onPreviewClear()
                            },
                            onDragCancel = {
                                dragOffset = Offset.Zero; active = false; onModelTouch(false); onPreviewClear()
                            }
                        )
                    }
            )

            // One-row tiles are too short for remove and resize to stack on the
            // right edge, so the remove button moves to the top-left corner.
            val shortTile = heightDp < 84.dp
            FilledIconButton(
                onClick = { onRemove(index) },
                modifier = Modifier.align(if (shortTile) Alignment.TopStart else Alignment.TopEnd).padding(4.dp).size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = DashColors.Warning, contentColor = Color.Black
                )
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dash_remove_tile, item.describe()), modifier = Modifier.size(18.dp))
            }

            // Bottom-right resize handle: drag to change the cell span.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DashColors.Accent.copy(alpha = 0.85f))
                    .pointerInput(index, item.x, item.y, item.w, item.h, cellWpx, cellHpx) {
                        detectDragGestures(
                            onDragStart = {
                                active = true; onModelTouch(true)
                                onPreview(item.x, item.y, snapW(), snapH(), canPlace(index, item.x, item.y, snapW(), snapH()))
                            },
                            onDrag = { change, delta ->
                                change.consume(); resizeExtra += delta
                                onPreview(item.x, item.y, snapW(), snapH(), canPlace(index, item.x, item.y, snapW(), snapH()))
                            },
                            onDragEnd = {
                                onResizeCell(index, snapW(), snapH())
                                resizeExtra = Offset.Zero; active = false; onModelTouch(false); onPreviewClear()
                            },
                            onDragCancel = {
                                resizeExtra = Offset.Zero; active = false; onModelTouch(false); onPreviewClear()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInFull,
                    contentDescription = stringResource(R.string.dash_resize_tile, item.describe()),
                    tint = DashColors.Background,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Renders the inner content of one dashboard tile (widget card, app shortcut,
 * split pair, or hosted system widget). Shared by the horizontal row layout and
 * the vertical split-screen stack so both look identical.
 */
@Composable
internal fun TileContent(
    item: DashboardItem,
    editing: Boolean,
    appsByPackage: Map<String, AppEntry>,
    mediaState: MediaState,
    mediaController: CarMediaController,
    hasMediaAccess: Boolean,
    context: android.content.Context,
    obdData: ObdData,
    obdConnection: ObdConnectionState,
    onConnectObd: () -> Unit,
    onPickDevice: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onLaunchSplitPair: (String, String) -> Unit,
    onEditLaunchBar: () -> Unit,
    onModelTouch: (Boolean) -> Unit,
    /** Grid only: grow this tile to at least the given pixel size (a docked window's minimum). */
    onFitToWindow: ((Int, Int) -> Unit)? = null
) {
    val env = SkinTileEnv(
        editing, appsByPackage, mediaState, mediaController, hasMediaAccess, context,
        obdData, obdConnection, onConnectObd, onPickDevice, onLaunchApp, onEditLaunchBar
    )
    if (skinHandles(item)) {
        SkinTile(item, env)
        return
    }
    when (item) {
        // The tiles a skin can redraw have one standard renderer, shared with the
        // skins' own fallback, so the two never drift apart.
        is DashboardItem.LaunchBar, is DashboardItem.AppShortcut -> StandardSkinnedTile(item, env)

        is DashboardItem.AppWindow -> {
            val label = appsByPackage[item.packageName]?.label ?: item.packageName.substringAfterLast('.')
            // While arranging, the window would cover its own tile's handles.
            if (editing) EditPlaceholder(icon = Icons.Filled.OpenInNew, label = stringResource(R.string.dash_app_window, label))
            else PipAnchorCard(modifier = Modifier.fillMaxSize(), packageName = item.packageName, appLabel = label, onWindowBiggerThanTile = onFitToWindow)
        }

        is DashboardItem.SplitPair -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            SplitPairTile(
                primaryApp = appsByPackage[item.primaryPackage],
                secondaryApp = appsByPackage[item.secondaryPackage],
                primaryPackage = item.primaryPackage,
                secondaryPackage = item.secondaryPackage,
                editing = editing,
                onClick = { onLaunchSplitPair(item.primaryPackage, item.secondaryPackage) }
            )
        }

        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.NAVMAP -> if (editing) {
                EditPlaceholder(icon = Icons.Filled.Navigation, label = BuiltinKind.NAVMAP.label)
            } else if (isEmulator) {
                // MapLibre's native renderer segfaults on the emulator's software GL.
                EditPlaceholder(icon = Icons.Filled.Navigation, label = stringResource(R.string.dash_map_needs_gpu), hint = stringResource(R.string.dash_not_on_emulator))
            } else Box(
                modifier = Modifier.fillMaxSize().background(DashColors.Card)
            ) {
                MapLibrePanel(modifier = Modifier.fillMaxSize())
                // Google Maps / Waze next turn, floated over the MapLibre map.
                val nav by NavDirections.state.collectAsState()
                if (nav.active) {
                    DirectionsBanner(
                        nav = nav,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
            }
            BuiltinKind.NAVIGATION, BuiltinKind.MEDIA, BuiltinKind.TELEMETRY,
            BuiltinKind.RANGE, BuiltinKind.SPEED_HUD, BuiltinKind.CLOCK, BuiltinKind.WEATHER ->
                StandardSkinnedTile(item, env)
            BuiltinKind.OBD_DTC -> ObdDtcCard(
                connection = obdConnection,
                onConnect = onConnectObd,
                modifier = Modifier.fillMaxSize(),
                onPickDevice = onPickDevice
            )
            BuiltinKind.OBD_ALL -> ObdAllCard(
                obdData = obdData,
                connection = obdConnection,
                onConnect = onConnectObd,
                modifier = Modifier.fillMaxSize(),
                onPickDevice = onPickDevice
            )
            BuiltinKind.DOORS -> DoorsCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CAN_MON -> CanMonitorCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.COMPASS -> CompassCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.PIP_ANCHOR -> if (editing) {
                EditPlaceholder(icon = Icons.Filled.Map, label = BuiltinKind.PIP_ANCHOR.label)
            } else PipAnchorCard(modifier = Modifier.fillMaxSize(), onWindowBiggerThanTile = onFitToWindow)
            BuiltinKind.TRIP -> TripCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.GFORCE -> GForceCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.PARKING -> ParkingCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CALENDAR -> CalendarCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.QUICK_DIAL -> QuickDialCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.NOTIFICATIONS -> NotificationsCard(hasAccess = hasMediaAccess, modifier = Modifier.fillMaxSize())
            BuiltinKind.AUDIO -> AudioCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CAR3D -> if (editing) {
                EditPlaceholder(icon = Icons.Filled.DirectionsCar, label = BuiltinKind.CAR3D.label)
            } else if (isEmulator) {
                // Filament fails the same way as the map on the emulator.
                EditPlaceholder(icon = Icons.Filled.DirectionsCar, label = stringResource(R.string.dash_car3d_needs_gpu), hint = stringResource(R.string.dash_not_on_emulator))
            } else Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DashColors.Card)
                    // While a finger is on the model, block dashboard swiping so
                    // touches only rotate/zoom the 3D car.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                onModelTouch(ev.changes.any { it.pressed })
                            }
                        }
                    }
            ) {
                Car3DPanel(modifier = Modifier.fillMaxSize())
            }
        }

        is DashboardItem.SystemWidget -> if (editing) {
            EditPlaceholder(icon = Icons.Filled.Widgets, label = stringResource(R.string.dash_app_widget))
        } else Card(
            modifier = Modifier.fillMaxSize()
        ) {
            HostedSystemWidget(appWidgetId = item.appWidgetId, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun AddTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .itemFill(DashColors.Card, CircleShape, rim = null),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.dash_add), tint = DashColors.Accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.dash_add), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

/** Short spoken name for a tile, for the edit controls' accessibility labels. */
@Composable
private fun DashboardItem.describe(): String = when (this) {
    is DashboardItem.BuiltinWidget -> kind.label
    is DashboardItem.AppShortcut -> packageName.substringAfterLast('.')
    is DashboardItem.SplitPair -> stringResource(R.string.dash_describe_split_pair)
    is DashboardItem.LaunchBar -> stringResource(R.string.dash_describe_launch_bar)
    is DashboardItem.SystemWidget -> stringResource(R.string.dash_describe_widget)
    is DashboardItem.AppWindow -> stringResource(R.string.dash_app_window, packageName.substringAfterLast('.'))
}

/**
 * Identity of a tile for Compose keys: what it is plus which occurrence it is,
 * so two shortcuts to the same app still get distinct keys.
 */
internal fun tileKeys(items: List<DashboardItem>): List<String> {
    fun id(item: DashboardItem) = when (item) {
        is DashboardItem.AppShortcut -> "app:${item.packageName}"
        is DashboardItem.SplitPair -> "split:${item.primaryPackage}|${item.secondaryPackage}"
        is DashboardItem.LaunchBar -> "bar"
        is DashboardItem.BuiltinWidget -> "builtin:${item.kind.name}"
        is DashboardItem.SystemWidget -> "widget:${item.appWidgetId}"
        is DashboardItem.AppWindow -> "appwin:${item.packageName}"
    }
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val me = id(item)
        val occurrence = seen[me] ?: 0
        seen[me] = occurrence + 1
        "$me#$occurrence"
    }
}

/** Static stand-in for a view-hosting tile while the dashboard is being arranged. */
@Composable
internal fun EditPlaceholder(
    icon: ImageVector,
    label: String,
    hint: String = stringResource(R.string.dash_shown_while_arranging)
) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            Text(hint, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
