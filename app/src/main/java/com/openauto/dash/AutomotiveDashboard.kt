@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentWithReceiverOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

/** How many layout steps Undo can walk back while arranging. */
internal const val MAX_UNDO = 30

/**
 * Gap between the docked Maps window and the divider. The system claims the
 * 30 dp band around a freeform window as its resize handle (WindowManager's
 * RESIZE_HANDLE_WIDTH_IN_DP) and swallows drags that start in it; the divider
 * has to start outside that band to receive its drag at all.
 */
private val DOCK_RESIZE_CLEARANCE = 32.dp

/**
 * Simple car launcher: three swipeable "virtual desktop" dashboards. Each page
 * is a grid the user fills with app shortcuts and widgets (our built-in Maps /
 * media / OBD cards, or any real Android app-widget) via the "+" tile. A single
 * Apps button opens the full app drawer. Nothing launches automatically.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AutomotiveDashboard(inSplitMode: Boolean = false) {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(DashThemeStore.load(context)) }
    var appearance by remember { mutableStateOf(DashThemeStore.loadAppearance(context)) }
    var effects by remember { mutableStateOf(DashThemeStore.loadEffects(context)) }
    val themeState = ThemeState(
        mode = themeMode, appearance = appearance, effects = effects,
        onMode = { themeMode = it; DashThemeStore.save(context, it) },
        onAppearance = { appearance = it; DashThemeStore.saveAppearance(context, it) },
        onEffects = { effects = it; DashThemeStore.saveEffects(context, it) }
    )
    var layout by remember { mutableStateOf(DashLayoutStore.load(context)) }
    var dockFraction by remember { mutableFloatStateOf(DashLayoutStore.loadDockFraction(context)) }
    // The half-width dashboard beside a Maps dock keeps its own arrangement.
    fun variantOf(l: DashLayout) = if (l == DashLayout.GRID) "" else "_half"
    // Read when called, never captured: gesture handlers outlive a composition,
    // and a stale arrangement would save one layout's tiles over the other's.
    fun variant() = variantOf(layout)
    var showTemplates by remember { mutableStateOf(false) }
    // The Settings screen, on the tab it was opened to; null while closed.
    var settingsTab by remember { mutableStateOf<SettingsTab?>(null) }
    DashColors.Sync(themeMode, appearance, effects)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val mediaController = remember { CarMediaController(context) }
    val updateManager = remember { UpdateManager(context) }
    // The live readings stay States: reading them here would recompose the whole
    // dashboard on every OBD sample. Tiles read them where they draw them.
    val obd = ObdBluetoothManager.data.collectAsState()
    val obdConnection by ObdBluetoothManager.connectionState.collectAsState()
    // Demo mode (⋮ menu) plays its own made-up tracks in place of the real session.
    val demoState = DemoMode.active.collectAsState()
    val demoOn by demoState
    val realMedia = mediaController.mediaState.collectAsState()
    val demoMedia = DemoMode.media.collectAsState()
    val media = remember { derivedStateOf { if (demoState.value) demoMedia.value else realMedia.value } }
    val updateStatus by updateManager.status.collectAsState()

    // Enumerating every launchable app (labels + icons) is the slowest part of
    // a cold start, so it runs on IO; tiles render their placeholder until then.
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    // Bumped when an app is installed, removed or updated; the list is read again.
    var appsChanged by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val stop = AppLauncher.watchPackages(context) { appsChanged++ }
        onDispose { stop() }
    }
    LaunchedEffect(appsChanged) {
        // An update fires several callbacks in a row: read the list once they settle.
        if (appsChanged > 0) delay(1_000)
        apps = withContext(Dispatchers.IO) { AppLauncher.loadApps(context) }
    }
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }

    var pages by remember { mutableStateOf(DashboardStore.load(context, variant())) }
    // The other arrangement's window apps, cached (see windowAppsEverywhere); null = read again.
    var otherLayoutWindows by remember { mutableStateOf<Set<String>?>(null) }
    // Layout snapshots for Undo while arranging (newest last, capped).
    var history by remember { mutableStateOf<List<List<List<DashboardItem>>>>(emptyList()) }

    /** Switches layout, loading that layout's own arrangement (seeded from the current one the first time). */
    val switchLayout: (DashLayout) -> Unit = { next ->
        if (next != layout) {
            val nextVariant = variantOf(next)
            if (!DashboardStore.exists(context, nextVariant)) DashboardStore.save(context, pages, nextVariant)
            layout = next
            DashLayoutStore.save(context, next)
            pages = DashboardStore.load(context, nextVariant)
            history = emptyList()
            otherLayoutWindows = null
        }
    }
    // (page, index) of the launch bar whose apps are being edited.
    var launchBarEditor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // (page, tile index) whose design picker is open.
    var designPicker by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Pages 0-2 swipe sideways; the middle one also swipes up/down (see DashboardStore.COLUMN).
    // Home is the centre of the cross, so that is where the launcher starts.
    val pagerState = rememberPagerState(initialPage = DashboardStore.CENTER, pageCount = { DashboardStore.ROW.size })
    val columnState = rememberPagerState(
        initialPage = DashboardStore.COLUMN_HOME,
        pageCount = { DashboardStore.COLUMN.size }
    )
    /** The dashboard on screen, as an index into pages. */
    val currentPage by remember {
        derivedStateOf {
            if (pagerState.currentPage == DashboardStore.CENTER) DashboardStore.COLUMN[columnState.currentPage]
            else DashboardStore.ROW[pagerState.currentPage]
        }
    }
    /** Brings [page] on screen: back to the middle row first when it is above or below, and the reverse. */
    fun showPage(page: Int) {
        scope.launch {
            val row = DashboardStore.COLUMN.indexOf(page)
            if (row >= 0 && page != DashboardStore.CENTER) {
                pagerState.animateScrollToPage(DashboardStore.CENTER)
                columnState.animateScrollToPage(row)
            } else {
                columnState.animateScrollToPage(DashboardStore.COLUMN_HOME)
                pagerState.animateScrollToPage(DashboardStore.ROW.indexOf(page))
            }
        }
    }

    // After a page change the floating cross shows for a moment, then fades.
    var pageIndicatorShown by remember { mutableStateOf(false) }
    var pageIndicatorFor by remember { mutableIntStateOf(currentPage) }
    LaunchedEffect(currentPage) {
        if (currentPage != pageIndicatorFor) {
            pageIndicatorFor = currentPage
            pageIndicatorShown = true
            delay(PAGE_INDICATOR_MS)
            pageIndicatorShown = false
        }
    }
    var showAllApps by remember { mutableStateOf(false) }
    var showSplitPicker by remember { mutableStateOf(false) }
    var showSplitEnable by remember { mutableStateOf(false) }
    var blockPagerSwipe by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // The Home key: close whatever is open and come back to the middle of the cross.
    val homePressed by MainActivity.homePressed.collectAsState()
    LaunchedEffect(homePressed) {
        if (homePressed > 0L) {
            showAllApps = false
            settingsTab = null
            editing = false
            showPage(DashboardStore.CENTER)
        }
    }
    var hasMediaAccess by remember { mutableStateOf(CarMediaController.hasNotificationAccess(context)) }
    // The demo's music and directions need no access grant.
    val mediaAccess = hasMediaAccess || demoOn
    var showDevicePicker by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // "+" add flow state.
    var addTargetPage by remember { mutableIntStateOf(-1) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showAppWindowPicker by remember { mutableStateOf(false) }
    var showWidgetMenu by remember { mutableStateOf(false) }
    var layoutNotice by remember { mutableStateOf<String?>(null) }
    // Two-step picker for creating a saved split-pair tile.
    var showPairPrimaryPicker by remember { mutableStateOf(false) }
    var showPairSecondaryPicker by remember { mutableStateOf(false) }
    var pairPrimaryPackage by remember { mutableStateOf<String?>(null) }

    // System-app install (root) — unlocks embedding the real Google Maps app.
    var showSystemDialog by remember { mutableStateOf(false) }

    // The drive lock (DriveLock.kt): while the car moves, arranging, settings
    // and pickers wait. Anything open when it engages closes; a locked tap
    // shows the notice chip for a moment instead of doing nothing.
    var lockWhileMoving by remember { mutableStateOf(DriveLockStore.load(context)) }
    val moving by rememberMoving(lockWhileMoving, demoOn)
    var lockNoticeAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lockNoticeAt) {
        if (lockNoticeAt > 0L) {
            delay(LOCK_NOTICE_MS)
            lockNoticeAt = 0L
        }
    }
    /** Runs [action] now, or shows the parked-only notice while moving. */
    fun whenParked(action: () -> Unit) {
        if (moving) lockNoticeAt = System.currentTimeMillis() else action()
    }
    LaunchedEffect(moving) {
        if (moving) {
            editing = false
            settingsTab = null
            showTemplates = false
            showSystemDialog = false
            showAddMenu = false
            showAppPicker = false
            showAppWindowPicker = false
            showWidgetMenu = false
            showPairPrimaryPicker = false
            showPairSecondaryPicker = false
            launchBarEditor = null
            designPicker = null
        }
    }

    // Docked app windows sit above dialogs and menus on this head unit. Those
    // report where they are (keepClearOfWindows), so only a window they overlap
    // steps aside. The app drawer covers everything, so it sends every window
    // aside. A page swipe, sideways or up/down, must take the pages' windows
    // along at once, but the Maps dock beside the pages does not move with them
    // and stays put.
    LaunchedEffect(showAllApps, settingsTab) { PipAnchor.steppedAside.value = showAllApps || settingsTab != null }
    val pageSwiping = pagerState.isScrollInProgress || columnState.isScrollInProgress
    LaunchedEffect(pageSwiping) { PipAnchor.pageSwiping.value = pageSwiping }

    /** Apps shown in a window by [items]' tiles. */
    fun tileWindowApps(items: List<DashboardItem>): Set<String> = items.mapNotNullTo(HashSet()) {
        when {
            it is DashboardItem.BuiltinWidget && it.kind == BuiltinKind.PIP_ANCHOR -> PipAnchor.MAPS_PACKAGE
            it is DashboardItem.AppWindow -> it.packageName
            else -> null
        }
    }

    /** Apps shown in a window by [items]' tiles, plus Maps when a layout docks it beside the pages. */
    fun windowApps(items: List<DashboardItem>): Set<String> =
        tileWindowApps(items) + if (layout != DashLayout.GRID) setOf(PipAnchor.MAPS_PACKAGE) else emptySet()

    /**
     * Window apps of every page, in both arrangements (a tile in the other layout
     * still owns its window). The other arrangement is read from storage once and
     * kept until it can change (a layout switch or a template), not on every swipe.
     */
    fun windowAppsEverywhere(): Set<String> {
        val other = otherLayoutWindows ?: run {
            val variant = if (variant() == "") "_half" else ""
            (if (DashboardStore.exists(context, variant)) tileWindowApps(DashboardStore.load(context, variant).flatten()) else emptySet())
                .also { otherLayoutWindows = it }
        }
        return windowApps(pages.flatten()) + other
    }

    // As soon as the current page changes (mid-swipe), clear the windows whose
    // tile is not on the new page; waiting for the old page to be disposed left
    // a strip of the window visible for a few seconds after the swipe. They are
    // parked aside, still running, so a navigation or a song goes on.
    LaunchedEffect(currentPage, pages, layout) {
        PipAnchor.placedPackages.value = windowAppsEverywhere()
        PipAnchor.stashAllExcept(context, windowApps(pages.getOrNull(currentPage).orEmpty()))
    }
    var rootChecked by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var systemBusy by remember { mutableStateOf(false) }
    var systemInstalled by remember { mutableStateOf(false) }
    var systemMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showSystemDialog) {
        if (showSystemDialog && !rootChecked) {
            rootAvailable = withContext(Dispatchers.IO) { SystemInstaller.isRootAvailable() }
            rootChecked = true
        }
    }

    // The privileged /system install used to run automatically on first launch
    // so the Maps tile could embed Google Maps. Embedding turned out to need
    // platform signing, so priv-app status buys nothing: the install is now
    // only offered from the system dialog (Build icon), never run unasked.

    /** Replaces one page, remembering the previous layout for Undo, and persists. */
    fun mutatePage(page: Int, transform: (List<DashboardItem>) -> List<DashboardItem>) {
        val before = pages
        val after = before.mapIndexed { i, list -> if (i == page) transform(list) else list }
        if (after == before) return
        history = (history + listOf(before)).takeLast(MAX_UNDO)
        pages = after
        DashboardStore.save(context, pages, variant())
    }

    /** Adds at the first free cell; returns the new tile's index, or -1 when the page is full. */
    fun addItemAt(page: Int, item: DashboardItem): Int {
        val list = pages.getOrNull(page) ?: return -1
        val cell = DashboardStore.firstFreeCell(list, item.w, item.h)
            ?: DashboardStore.firstFreeCell(list, item.minW(), item.minH())
        if (cell == null) {
            layoutNotice = context.getString(R.string.dash_notice_no_space)
            return -1
        }
        val fits = DashboardStore.canPlace(list, null, cell.first, cell.second, item.w, item.h)
        val placed = if (fits) item.withCell(cell.first, cell.second, item.w, item.h)
            else item.withCell(cell.first, cell.second, item.minW(), item.minH())
        mutatePage(page) { it + placed }
        return list.size
    }

    /** Add only when the tile fits; a full page must never create a hidden overlap. */
    fun addItem(page: Int, item: DashboardItem): Boolean = addItemAt(page, item) >= 0

    /** Removing an app's last window tile ends the tile's keep-it-open duty. */
    fun releaseMapsAnchorIfGone() {
        // Both arrangements count: a window tile that exists only in the other
        // layout must keep its app's keep-open intent.
        val keep = windowAppsEverywhere()
        PipAnchor.placedPackages.value = keep
        PipAnchor.releaseAutoOpenExcept(context, keep)
    }
    // A layout switch adds or removes the Maps dock; redo the keep-open bookkeeping.
    LaunchedEffect(layout) { releaseMapsAnchorIfGone() }

    fun removeAt(page: Int, index: Int) {
        val item = pages.getOrNull(page)?.getOrNull(index) ?: return
        if (item is DashboardItem.SystemWidget) WidgetHostHolder.delete(context, item.appWidgetId)
        mutatePage(page) { list -> list.filterIndexed { i, _ -> i != index } }
        releaseMapsAnchorIfGone()
    }

    /** Replaces the tile at [index] (same cell) with [item], e.g. an edited launch bar. */
    fun updateItem(page: Int, index: Int, item: DashboardItem) {
        mutatePage(page) { list ->
            list.mapIndexed { i, old -> if (i == index) item.withCell(old.x, old.y, old.w, old.h) else old }
        }
    }

    // Move a tile's top-left to grid cell (x, y), keeping its span. A tile in
    // the way is swapped or nudged aside; only a page with no room refuses.
    fun moveCell(page: Int, index: Int, x: Int, y: Int) {
        val list = pages.getOrNull(page) ?: return
        val resolved = DashboardStore.moveResolving(list, index, x, y)
        if (resolved == null) {
            layoutNotice = context.getString(R.string.dash_notice_no_room_to_move)
            return
        }
        mutatePage(page) { resolved }
    }

    // Resize a tile to span w x h cells, keeping its top-left. Width/height are
    // capped at the grid edge from the tile's position so it grows in place
    // instead of being shoved left/up to make a too-big span fit.
    fun resizeCell(page: Int, index: Int, w: Int, h: Int) {
        val list = pages.getOrNull(page) ?: return
        val it = list.getOrNull(index) ?: return
        val cw = w.coerceIn(it.minW(), GRID_COLS - it.x)
        val ch = h.coerceIn(it.minH(), GRID_ROWS - it.y)
        if (!DashboardStore.canPlace(list, index, it.x, it.y, cw, ch)) {
            layoutNotice = context.getString(R.string.dash_notice_resize_overlap)
            return
        }
        mutatePage(page) { l -> l.mapIndexed { i, t -> if (i == index) t.withCell(t.x, t.y, cw, ch) else t } }
    }

    /** Draws the tile at [index] with its text and icons [zoom] times their size. */
    fun zoomTile(page: Int, index: Int, zoom: Float) {
        mutatePage(page) { list -> list.mapIndexed { i, t -> if (i == index) t.withZoom(zoom) else t } }
    }

    /** Restores the layout from before the last add / move / resize / remove. */
    fun undo() {
        val previous = history.lastOrNull() ?: return
        history = history.dropLast(1)
        pages = previous
        DashboardStore.save(context, pages, variant())
    }

    /** Clears one page (releasing any hosted app-widgets); Undo brings it back. */
    fun resetPage(page: Int) {
        pages.getOrNull(page)?.filterIsInstance<DashboardItem.SystemWidget>()
            ?.forEach { WidgetHostHolder.delete(context, it.appWidgetId) }
        mutatePage(page) { emptyList() }
        releaseMapsAnchorIfGone()
    }

    /** Replaces every page at once (a template); one Undo step brings them all back. */
    fun mutateAll(after: List<List<DashboardItem>>) {
        if (after == pages) return
        history = (history + listOf(pages)).takeLast(MAX_UNDO)
        pages = after
        DashboardStore.save(context, pages, variant())
    }

    val screenConfig = LocalConfiguration.current
    /** The car and screen a template is placed for, in the full-width ([half] false) or docked arrangement. */
    fun templateScreen(half: Boolean): TemplateScreen = TemplateScreen.of(
        pageWidthDp = screenConfig.screenWidthDp * if (half) 1f - dockFraction else 1f,
        // Roughly what the bars leave the grid.
        pageHeightDp = screenConfig.screenHeightDp * 0.8f,
        obdPaired = ObdBluetoothManager.savedDeviceAddress() != null || obdConnection == ObdConnectionState.CONNECTED,
        driverOnRight = CarProfileStore.current.driverOnRight,
        mapsDocked = half,
        dockApps = TemplatePlacer.dockApps(pages, appsByPackage.keys)
    )

    /**
     * Lays [template] out on every page ([replaceAll]) or only on the empty
     * ones. The other arrangement (full width / beside the Maps dock) gets it
     * too if the user has never set that one up.
     */
    fun applyTemplate(template: DashTemplate, replaceAll: Boolean) {
        val built = TemplatePlacer.pages(template, templateScreen(variant() != ""))
        if (replaceAll) {
            pages.flatten().filterIsInstance<DashboardItem.SystemWidget>()
                .forEach { WidgetHostHolder.delete(context, it.appWidgetId) }
        }
        mutateAll(pages.mapIndexed { p, old -> if (replaceAll || old.isEmpty()) built[p] else old })
        val other = if (variant() == "") "_half" else ""
        if (!DashboardStore.exists(context, other)) {
            DashboardStore.save(context, TemplatePlacer.pages(template, templateScreen(other != "")), other)
            otherLayoutWindows = null
        }
        releaseMapsAnchorIfGone()
    }

    // System app-widget picker; adds the bound widget to the page that requested it.
    val addSystemWidget = rememberSystemWidgetAdder { id ->
        if (addTargetPage in 0 until DashboardStore.PAGE_COUNT) {
            if (!addItem(addTargetPage, DashboardItem.SystemWidget(id))) {
                // The picker already allocated a host ID. Release it if the
                // layout cannot accept the widget, otherwise it leaks unused IDs.
                WidgetHostHolder.delete(context, id)
            }
        }
    }

    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClock()
            // The bar shows HH:mm, so wake at the next minute boundary (+ a beat).
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    // Observe media; re-check notification access on resume so granting it in
    // system settings takes effect without an app restart. The OBD link, its
    // poll and the reconnect run in VehicleMonitor, for the whole process; the
    // screen only says whether the launcher is in front.
    DisposableEffect(lifecycleOwner) {
        ObdBluetoothManager.setContext(context)
        McuReader.setContext(context)
        CarProfileStore.setContext(context)
        SpeedCorrection.setContext(context)
        CarCare.setContext(context)
        Maintenance.setContext(context)
        PidExplorer.setContext(context)
        AiMechanic.setContext(context)
        StartupBriefing.start(context)
        VehicleMonitor.start(context)
        mediaController.start()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasMediaAccess = CarMediaController.hasNotificationAccess(context)
                    if (hasMediaAccess) mediaController.start()
                    VehicleMonitor.connectSaved()
                    VehicleMonitor.setForeground(true)
                }
                Lifecycle.Event.ON_PAUSE -> VehicleMonitor.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            VehicleMonitor.setForeground(false)
            mediaController.stop()
        }
    }

    LaunchedEffect(Unit) { updateManager.checkForUpdate() }

    // A check asked for from the menu says how it went, "up to date" included;
    // that one clears itself after a few seconds.
    var manualUpdateCheck by remember { mutableStateOf(false) }
    LaunchedEffect(manualUpdateCheck, updateStatus) {
        if (manualUpdateCheck && updateStatus is UpdateStatus.UpToDate) {
            delay(5_000)
            manualUpdateCheck = false
        }
    }
    val checkForUpdates: () -> Unit = {
        manualUpdateCheck = true
        // A download already under way shows its own progress; don't restart it.
        if (updateStatus !is UpdateStatus.Downloading && updateStatus !is UpdateStatus.Installing) {
            scope.launch { updateManager.checkForUpdate() }
        }
    }

    val onUpdate: (UpdateInfo) -> Unit = { info ->
        if (updateManager.canInstallPackages()) {
            scope.launch { updateManager.downloadAndInstall(info) }
        } else {
            updateManager.openInstallPermissionSettings()
        }
    }

    fun openDevicePicker() {
        pairedDevices = ObdBluetoothManager.bondedDevices()
        showDevicePicker = true
    }

    fun connectSavedOrPick() {
        val saved = ObdBluetoothManager.savedDeviceAddress()
        if (saved != null) scope.launch { ObdBluetoothManager.connect(saved) } else openDevicePicker()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) connectSavedOrPick()
    }

    val ensureBluetooth: (() -> Unit) -> Unit = { action ->
        val missing = requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else action()
    }

    val onConnectObd: () -> Unit = { ensureBluetooth { connectSavedOrPick() } }
    val onPickDevice: () -> Unit = { ensureBluetooth { openDevicePicker() } }

    val onLaunchApp: (String) -> Unit = { pkg ->
        if (AppLauncher.launch(context, pkg)) showAllApps = false
    }

    val onLaunchSplitPair: (String, String) -> Unit = { primary, secondary ->
        if (SplitLauncher.isSystemSplitAvailable()) SplitLauncher.launchSplitPair(context, primary, secondary)
        else showSplitEnable = true
    }

    val onAdd: (Int) -> Unit = { page ->
        whenParked {
            addTargetPage = page
            showAddMenu = true
        }
    }

    // Android forces the status bar on whenever a floating (freeform) window is
    // on screen, i.e. while a Maps window is docked. The head unit's bar paints
    // over its strip and takes every tap there, so the dashboard lays out below
    // it; the bar already shows the time, so the launcher bar swaps its clock
    // for the page dots. Not via WindowInsets.statusBars: on Android 10 that
    // stays at the bar's height even while the bar is hidden, which pushed the
    // whole dashboard down permanently.
    val dockedApps by PipAnchor.dockedPackages.collectAsState()
    val barForced = dockedApps.isNotEmpty()
    val density = LocalDensity.current
    val rootView = LocalView.current
    val statusBarPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    // Only the part of the bar that really covers the dashboard is reserved.
    // When the bar comes up, the head unit already shifts the window's content
    // down below it while still reporting the bar's full height as an inset;
    // padding by that inset again left an empty strip as tall as the bar
    // between the bar and the tiles. So the padding is the bar's bottom edge
    // minus where the content actually starts on the screen, never less than 0.
    var contentTopPx by remember { mutableIntStateOf(0) }
    // Everything the bar shows and can do, built once: the bar, its menu and
    // the Settings screen all read the same model.
    val settingsModel = TopBarModel(
        clock = clock,
        versionName = updateManager.currentVersionName,
        obdConnection = obdConnection,
        obd = obd,
        editing = editing,
        layout = layout,
        onLayout = switchLayout,
        onApps = { showAllApps = true },
        onConnectObd = onConnectObd,
        onSplit = {
            if (SplitLauncher.isSystemSplitAvailable()) showSplitPicker = true
            else showSplitEnable = true
        },
        onToggleEdit = { if (editing) editing = false else whenParked { editing = true } },
        onTemplates = { whenParked { showTemplates = true } },
        onSystem = { whenParked { showSystemDialog = true } },
        onCheckUpdates = checkForUpdates,
        demo = demoOn,
        onDemo = { DemoMode.toggle(context) },
        merged = barForced,
        page = currentPage,
        moving = moving,
        lockWhileMoving = lockWhileMoving,
        onLockWhileMoving = {
            lockWhileMoving = it
            DriveLockStore.save(context, it)
        },
        onSettings = { whenParked { settingsTab = SettingsTab.CAR } }
    )

    val barOverlapPx = if (barForced) (statusBarPx - contentTopPx).coerceAtLeast(0) else 0
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(dashBackground())
            .onGloballyPositioned { coords ->
                val origin = IntArray(2).also { rootView.getLocationOnScreen(it) }
                contentTopPx = origin[1] + coords.positionInRoot().y.roundToInt()
            }
            .padding(top = with(density) { barOverlapPx.toDp() })
    ) {

        Box(
            modifier = Modifier
                .weight(1f)
                // Docked app windows must stay inside this area, above the bar.
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInRoot()
                    val origin = IntArray(2).also { rootView.getLocationOnScreen(it) }
                    PipAnchor.allowedArea.value = ScreenRect(
                        (b.left + origin[0]).roundToInt(), (b.top + origin[1]).roundToInt(),
                        (b.right + origin[0]).roundToInt(), (b.bottom + origin[1]).roundToInt()
                    )
                }
        ) {
            // "Maps left" layout: a permanent Google Maps dock takes the left half
            // and never leaves composition, so the window is placed once and
            // swiping pages never touches it. Not while the OS itself has us in
            // split-screen: half of a half is too small for either.
            val dockSide = if (inSplitMode) null else when (layout) {
                DashLayout.MAPS_LEFT -> Alignment.Start
                DashLayout.MAPS_RIGHT -> Alignment.End
                DashLayout.GRID -> null
            }
            // movableContent: switching Maps left <-> right moves the same dock
            // instead of disposing and rebuilding it (which closed the window).
            // The side is a parameter, not a capture: the lambda is remembered
            // once, so a captured side would stay the first one composed.
            val mapsDock = remember {
                movableContentWithReceiverOf<RowScope, Alignment.Horizontal?> { side ->
                // Android treats the 30 dp around a freeform window as its
                // resize handle and takes any drag that starts there for the
                // system, so the divider must sit further away than that from
                // the Maps window's edge or dragging it does nothing.
                Box(
                    modifier = Modifier
                        .weight(dockFraction)
                        .fillMaxHeight()
                        .padding(
                            start = if (side == Alignment.Start) 8.dp else DOCK_RESIZE_CLEARANCE,
                            end = if (side == Alignment.End) 8.dp else DOCK_RESIZE_CLEARANCE,
                            top = 8.dp, bottom = 8.dp
                        )
                ) {
                    PipAnchorCard(modifier = Modifier.fillMaxSize(), isDock = true)
                }
                }
            }
            // Drag the divider to trade width between the dock and the pages; the
            // dock re-measures itself, so the Maps window follows once released.
            var rowWidthPx by remember { mutableIntStateOf(1) }
            val divider: @Composable RowScope.() -> Unit = {
                DockDivider(
                    onDrag = { dx ->
                        val delta = dx / rowWidthPx.coerceAtLeast(1)
                        val signed = if (dockSide == Alignment.Start) delta else -delta
                        dockFraction = (dockFraction + signed)
                            .coerceIn(DashLayoutStore.MIN_DOCK_FRACTION, DashLayoutStore.MAX_DOCK_FRACTION)
                    },
                    onDragEnd = { DashLayoutStore.saveDockFraction(context, dockFraction) }
                )
            }
            Row(modifier = Modifier.fillMaxSize().onSizeChanged { rowWidthPx = it.width }) {
            if (dockSide == Alignment.Start) { mapsDock(dockSide); divider() }
            /** One dashboard, by its index into pages. */
            val dashboardPage: @Composable (Int) -> Unit = { page ->
                DashboardPage(
                    pageItems = pages[page],
                    editing = editing,
                    inSplitMode = inSplitMode,
                    onModelTouch = { blockPagerSwipe = it },
                    appsByPackage = appsByPackage,
                    media = media,
                    mediaController = mediaController,
                    hasMediaAccess = mediaAccess,
                    obd = obd,
                    obdConnection = obdConnection,
                    onConnectObd = onConnectObd,
                    onPickDevice = onPickDevice,
                    onLaunchApp = onLaunchApp,
                    onLaunchSplitPair = onLaunchSplitPair,
                    onEditLaunchBar = { index -> whenParked { launchBarEditor = page to index } },
                    onRemove = { index -> removeAt(page, index) },
                    onMoveCell = { index, x, y -> moveCell(page, index, x, y) },
                    onResizeCell = { index, w, h -> resizeCell(page, index, w, h) },
                    canPlace = { index, x, y, w, h ->
                        DashboardStore.canPlace(pages[page], index, x, y, w, h)
                    },
                    canMove = { index, x, y ->
                        DashboardStore.moveResolving(pages[page], index, x, y) != null
                    },
                    onAdd = { onAdd(page) },
                    onTemplates = { whenParked { showTemplates = true } },
                    onDesign = { index -> whenParked { designPicker = page to index } },
                    onZoom = { index, zoom -> zoomTile(page, index, zoom) }
                )
            }
            // Sideways swipes only from the middle row: the pages above and
            // below the centre one have nothing beside them.
            val onHomeRow = columnState.currentPage == DashboardStore.COLUMN_HOME && !columnState.isScrollInProgress
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !blockPagerSwipe && onHomeRow,
                modifier = Modifier.weight(if (dockSide == null) 1f else 1f - dockFraction).fillMaxHeight()
            ) { index ->
                val page = DashboardStore.ROW[index]
                if (page == DashboardStore.CENTER) {
                    VerticalPager(
                        state = columnState,
                        userScrollEnabled = !blockPagerSwipe && !pagerState.isScrollInProgress,
                        // Off the home row a sideways swipe heads back to it: the
                        // row is the only way sideways, and a dead swipe feels broken.
                        modifier = Modifier.fillMaxSize().pointerInput(onHomeRow, blockPagerSwipe) {
                            if (onHomeRow || blockPagerSwipe) return@pointerInput
                            var dragged = 0f
                            val threshold = 48.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragEnd = { if (abs(dragged) >= threshold) showPage(DashboardStore.CENTER) }
                            ) { change, dx ->
                                dragged += dx
                                change.consume()
                            }
                        }
                    ) { row -> dashboardPage(DashboardStore.COLUMN[row]) }
                } else {
                    dashboardPage(page)
                }
            }
            if (dockSide == Alignment.End) { divider(); mapsDock(dockSide) }
            }

            // Floating swap button (bottom-centre), shown whenever the launcher
            // shares the screen — regardless of how the split was started (our
            // accessibility path or the OS's manual recents gesture). Swapping
            // Always in sight while the dashboard shows made-up data, with the way
            // out. The standard bar carries it itself; the skins' bars don't, so
            // there it floats over the pages.
            if (demoOn && DashColors.Skin != DashSkin.STANDARD) {
                DemoBadge(
                    onStop = DemoMode::stop,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                )
            }
            if (lockNoticeAt > 0L) {
                DriveLockChip(modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            }
            // Fades in fast and out slowly; drawn only while it shows at all.
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (pageIndicatorShown && !editing) 1f else 0f,
                animationSpec = tween(if (pageIndicatorShown) 150 else 400),
                label = "pageIndicator"
            )
            if (indicatorAlpha > 0f) {
                PageIndicator(
                    pageIndicatorFor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .graphicsLayer { alpha = indicatorAlpha }
                )
            }

            // needs the accessibility service; if it isn't on, tapping prompts to
            // enable it instead of silently doing nothing.
            if (inSplitMode) {
                FloatingActionButton(
                    onClick = {
                        if (SplitLauncher.isSystemSplitAvailable()) SplitLauncher.swapSplit()
                        else showSplitEnable = true
                    },
                    containerColor = DashColors.Accent,
                    contentColor = DashColors.OnAccent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .size(56.dp)
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = stringResource(R.string.dash_swap_split)
                    )
                }
            }

            settingsTab?.let { tab ->
                SettingsScreen(
                    m = settingsModel,
                    theme = themeState,
                    initialTab = tab,
                    onClose = { settingsTab = null },
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }

            if (showAllApps) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DashColors.Background.copy(alpha = 0.72f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showAllApps = false }
                ) {
                    AppDrawer(
                        apps = apps,
                        moving = moving,
                        onLaunch = { onLaunchApp(it.packageName) },
                        onClose = { showAllApps = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    )
                }
            }
        }

        // Edit bar and update banner sit just above the launcher bar for the same
        // reason: the OS status bar can cover the top strip and swallow its taps.
        if (editing && !inSplitMode) {
            EditBar(
                page = currentPage,
                canUndo = history.isNotEmpty(),
                onAdd = { onAdd(currentPage) },
                onUndo = { undo() },
                onReset = { resetPage(currentPage) },
                onTemplates = { showTemplates = true },
                onDone = { editing = false }
            )
        }

        UpdateBanner(
            status = updateStatus,
            currentVersion = updateManager.currentVersionName,
            showCheck = manualUpdateCheck,
            onUpdate = onUpdate,
            onDismiss = {
                manualUpdateCheck = false
                updateManager.dismiss()
            }
        )

        // The bar lives at the bottom: the OS status bar owns the top edge on
        // this head unit whenever a floating window is on screen, and it used to
        // cover the launcher bar there. A horizontal swipe across the bar (or
        // the page dots) changes page, for when a docked window covers the pages.
        // A parked window's cover sits in the bottom-right corner; the bar stops
        // short of it so its ⋮ button stays reachable.
        val coverShowing by ParkedCover.showing.collectAsState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (coverShowing) (ParkedCover.WIDTH_DP + 4).dp else 0.dp)
                .pointerInput(Unit) {
                    var dragged = 0f
                    val threshold = 48.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            val step = when {
                                dragged <= -threshold -> 1
                                dragged >= threshold -> -1
                                else -> 0
                            }
                            // Sideways along the middle row; from a page above or
                            // below the centre that means back to the row first.
                            if (step != 0) {
                                if (columnState.currentPage != DashboardStore.COLUMN_HOME) {
                                    showPage(DashboardStore.CENTER)
                                } else {
                                    val next = (pagerState.currentPage + step).coerceIn(0, DashboardStore.ROW.size - 1)
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                }
                            }
                        }
                    ) { change, dx ->
                        change.consume()
                        dragged += dx
                    }
                }
        ) {
        TopBar(settingsModel)

        }
    }

    designPicker?.let { (page, index) ->
        val tile = pages.getOrNull(page)?.getOrNull(index) as? DashboardItem.BuiltinWidget
        if (tile == null) {
            // The tile went away (undo, template): close, but not mid-composition.
            LaunchedEffect(Unit) { designPicker = null }
        } else {
            val env = SkinTileEnv(
                editing = false, appsByPackage = appsByPackage, media = media, mediaController = mediaController,
                hasMediaAccess = mediaAccess, context = context, obd = obd, obdConnection = obdConnection,
                onConnectObd = onConnectObd, onPickDevice = onPickDevice, onLaunchApp = onLaunchApp, onEditLaunchBar = {}
            )
            WidgetDesignPickerDialog(
                kind = tile.kind,
                current = tile.design,
                // Grid cells on the head unit are a little wider than tall.
                aspect = (tile.w * 1.1f) / tile.h,
                env = env,
                standardPreview = {
                    // Arranging mode: view-hosting tiles show their placeholder, not a second live map.
                    TileContent(
                        item = tile.copy(design = WidgetDesign.STANDARD), editing = true, appsByPackage = appsByPackage,
                        media = media, mediaController = mediaController, hasMediaAccess = mediaAccess,
                        context = context, obd = obd, obdConnection = obdConnection, onConnectObd = onConnectObd,
                        onPickDevice = onPickDevice, onLaunchApp = onLaunchApp, onLaunchSplitPair = onLaunchSplitPair,
                        onEditLaunchBar = {}, onModelTouch = {}
                    )
                },
                onPick = { design ->
                    updateItem(page, index, tile.copy(design = design))
                    designPicker = null
                },
                onDismiss = { designPicker = null }
            )
        }
    }

    launchBarEditor?.let { (page, index) ->
        val bar = pages.getOrNull(page)?.getOrNull(index) as? DashboardItem.LaunchBar
        if (bar == null) {
            LaunchedEffect(Unit) { launchBarEditor = null }
        } else {
            LaunchBarEditorDialog(
                apps = apps,
                appsByPackage = appsByPackage,
                packages = bar.packages,
                onSave = { pkgs ->
                    updateItem(page, index, bar.copy(packages = pkgs))
                    launchBarEditor = null
                },
                onDismiss = { launchBarEditor = null }
            )
        }
    }

    layoutNotice?.let { notice ->
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { layoutNotice = null },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_notice_title), color = DashColors.TextPrimary) },
            text = { Text(notice, color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { layoutNotice = null }) {
                    Text(stringResource(R.string.dash_got_it), color = DashColors.Accent)
                }
            }
        )
    }

    if (showTemplates) {
        DashTemplateDialog(
            screen = templateScreen(variant() != ""),
            onApply = { template, replaceAll ->
                applyTemplate(template, replaceAll)
                showTemplates = false
                showPage(DashboardStore.CENTER)
            },
            onDismiss = { showTemplates = false }
        )
    }

    if (showDevicePicker) {
        DevicePickerDialog(
            devices = pairedDevices,
            onPick = { mac ->
                ObdBluetoothManager.saveDeviceAddress(mac)
                showDevicePicker = false
                scope.launch { ObdBluetoothManager.connect(mac) }
            },
            onDismiss = { showDevicePicker = false },
            onOpenSettings = {
                showDevicePicker = false
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
    }

    if (showAddMenu) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { showAddMenu = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_add_to_dashboard), color = DashColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddChoiceRow(Icons.Filled.Apps, stringResource(R.string.dash_add_app)) {
                        showAddMenu = false
                        showAppPicker = true
                    }
                    AddChoiceRow(Icons.Filled.OpenInNew, stringResource(R.string.dash_add_app_window)) {
                        showAddMenu = false
                        showAppWindowPicker = true
                    }
                    AddChoiceRow(Icons.Filled.Splitscreen, stringResource(R.string.dash_add_app_pair)) {
                        showAddMenu = false
                        pairPrimaryPackage = null
                        showPairPrimaryPicker = true
                    }
                    AddChoiceRow(Icons.Filled.Widgets, stringResource(R.string.dash_add_widget)) {
                        showAddMenu = false
                        showWidgetMenu = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddMenu = false }) {
                    Text(stringResource(R.string.dash_cancel), color = DashColors.Muted)
                }
            }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps,
            onPick = { app ->
                showAppPicker = false
                if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.AppShortcut(app.packageName))
            },
            onDismiss = { showAppPicker = false }
        )
    }

    if (showAppWindowPicker) {
        AppPickerDialog(
            apps = apps,
            onPick = { app ->
                showAppWindowPicker = false
                if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.AppWindow(app.packageName))
            },
            onDismiss = { showAppWindowPicker = false }
        )
    }

    if (showSplitPicker) {
        AppPickerDialog(
            apps = apps,
            title = stringResource(R.string.dash_split_with),
            onPick = { app ->
                showSplitPicker = false
                SplitLauncher.launchSplit(context, app.packageName)
            },
            onDismiss = { showSplitPicker = false }
        )
    }

    if (showPairPrimaryPicker) {
        AppPickerDialog(
            apps = apps,
            title = stringResource(R.string.dash_pair_first),
            onPick = { app ->
                pairPrimaryPackage = app.packageName
                showPairPrimaryPicker = false
                showPairSecondaryPicker = true
            },
            onDismiss = { showPairPrimaryPicker = false }
        )
    }

    if (showPairSecondaryPicker) {
        AppPickerDialog(
            apps = apps,
            title = stringResource(R.string.dash_pair_second),
            onPick = { app ->
                showPairSecondaryPicker = false
                val primary = pairPrimaryPackage
                pairPrimaryPackage = null
                if (primary != null && addTargetPage >= 0) {
                    addItem(addTargetPage, DashboardItem.SplitPair(primary, app.packageName))
                }
            },
            onDismiss = {
                showPairSecondaryPicker = false
                pairPrimaryPackage = null
            }
        )
    }

    if (showSplitEnable) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { showSplitEnable = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_enable_split_title), color = DashColors.TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.dash_enable_split_body),
                    color = DashColors.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSplitEnable = false
                    SplitLauncher.openAccessibilitySettings(context)
                }) {
                    Text(stringResource(R.string.dash_open_settings), color = DashColors.TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitEnable = false }) {
                    Text(stringResource(R.string.dash_cancel), color = DashColors.Muted)
                }
            }
        )
    }

    if (showWidgetMenu) {
        WidgetPickerDialog(
            onPickBuiltin = { kind ->
                showWidgetMenu = false
                if (addTargetPage >= 0) {
                    addItem(addTargetPage, DashboardItem.BuiltinWidget(kind, w = kind.defaultW, h = kind.defaultH))
                }
            },
            onPickLaunchBar = {
                showWidgetMenu = false
                if (addTargetPage >= 0) {
                    // Open the editor right away so the new bar isn't left empty.
                    val index = addItemAt(addTargetPage, DashboardItem.LaunchBar())
                    if (index >= 0) launchBarEditor = addTargetPage to index
                }
            },
            onPickSystemWidget = {
                showWidgetMenu = false
                addSystemWidget.pickFromList()
            },
            onDismiss = { showWidgetMenu = false }
        )
    }

    if (showSystemDialog) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { if (!systemBusy) showSystemDialog = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_system_app_title), color = DashColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.dash_system_app_body, AdbInstaller.DEFAULT_PORT),
                        color = DashColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = when {
                            !rootChecked -> stringResource(R.string.dash_root_checking)
                            rootAvailable -> stringResource(R.string.dash_root_available)
                            else -> stringResource(R.string.dash_root_missing, AdbInstaller.DEFAULT_PORT)
                        },
                        color = if (rootAvailable) DashColors.Good else DashColors.Muted,
                        style = MaterialTheme.typography.labelLarge
                    )
                    systemMessage?.let {
                        Text(
                            it,
                            color = if (systemInstalled) DashColors.Good else DashColors.Warning,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (systemBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = DashColors.Accent
                        )
                    }
                }
            },
            confirmButton = {
                if (systemInstalled) {
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { SystemInstaller.rebootDevice(context) } } }) {
                        Text(stringResource(R.string.dash_reboot_now), color = DashColors.Accent)
                    }
                } else {
                    TextButton(
                        enabled = !systemBusy,
                        onClick = {
                            systemBusy = true
                            systemMessage = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    SystemInstaller.install(context)
                                }
                                systemBusy = false
                                res.onSuccess {
                                    systemInstalled = true
                                    systemMessage = context.getString(R.string.dash_system_installed)
                                }.onFailure {
                                    systemMessage = context.getString(R.string.dash_system_failed, it.message.orEmpty())
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.dash_install_system_app), color = DashColors.Accent)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!systemBusy) showSystemDialog = false }) {
                    Text(stringResource(R.string.dash_close), color = DashColors.Muted)
                }
            }
        )
    }

    // The phone's call, when the overlay window can't show it over other apps.
    PhoneCallHost()
}
