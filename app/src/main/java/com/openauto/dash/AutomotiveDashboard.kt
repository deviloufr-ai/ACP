@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

/** How many layout steps Undo can walk back while arranging. */
internal const val MAX_UNDO = 30

/**
 * Simple car launcher: three swipeable "virtual desktop" dashboards. Each page
 * is a grid the user fills with app shortcuts and widgets (our built-in Maps /
 * media / OBD cards, or any real Android app-widget) via the "+" tile. A single
 * Apps button opens the full app drawer. Nothing launches automatically.
 */
@Composable
fun AutomotiveDashboard(inSplitMode: Boolean = false) {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(DashThemeStore.load(context)) }
    var showThemePicker by remember { mutableStateOf(false) }
    DashColors.Sync(themeMode)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val mediaController = remember { CarMediaController(context) }
    val updateManager = remember { UpdateManager(context) }
    val obdData by ObdBluetoothManager.data.collectAsState()
    val obdConnection by ObdBluetoothManager.connectionState.collectAsState()
    val mediaState by mediaController.mediaState.collectAsState()
    val updateStatus by updateManager.status.collectAsState()

    // Enumerating every launchable app (labels + icons) is the slowest part of
    // a cold start, so it runs on IO; tiles render their placeholder until then.
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppLauncher.loadApps(context) }
    }
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }

    var pages by remember { mutableStateOf(DashboardStore.load(context)) }
    // Layout snapshots for Undo while arranging (newest last, capped).
    var history by remember { mutableStateOf<List<List<List<DashboardItem>>>>(emptyList()) }
    // (page, index) of the launch bar whose apps are being edited.
    var launchBarEditor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val pagerState = rememberPagerState(pageCount = { DashboardStore.PAGE_COUNT })

    var showAllApps by remember { mutableStateOf(false) }
    var showSplitPicker by remember { mutableStateOf(false) }
    var showSplitEnable by remember { mutableStateOf(false) }
    var blockPagerSwipe by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var hasMediaAccess by remember { mutableStateOf(CarMediaController.hasNotificationAccess(context)) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // "+" add flow state.
    var addTargetPage by remember { mutableIntStateOf(-1) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showWidgetMenu by remember { mutableStateOf(false) }
    var layoutNotice by remember { mutableStateOf<String?>(null) }
    // Two-step picker for creating a saved split-pair tile.
    var showPairPrimaryPicker by remember { mutableStateOf(false) }
    var showPairSecondaryPicker by remember { mutableStateOf(false) }
    var pairPrimaryPackage by remember { mutableStateOf<String?>(null) }

    // System-app install (root) — unlocks embedding the real Google Maps app.
    var showSystemDialog by remember { mutableStateOf(false) }
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
        DashboardStore.save(context, pages)
    }

    /** Adds at the first free cell; returns the new tile's index, or -1 when the page is full. */
    fun addItemAt(page: Int, item: DashboardItem): Int {
        val list = pages.getOrNull(page) ?: return -1
        val cell = DashboardStore.firstFreeCell(list, item.w, item.h)
            ?: DashboardStore.firstFreeCell(list, item.minW(), item.minH())
        if (cell == null) {
            layoutNotice = "This dashboard has no space for that tile. Remove, resize, or use another page."
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

    fun removeAt(page: Int, index: Int) {
        val item = pages.getOrNull(page)?.getOrNull(index) ?: return
        if (item is DashboardItem.SystemWidget) WidgetHostHolder.delete(context, item.appWidgetId)
        mutatePage(page) { list -> list.filterIndexed { i, _ -> i != index } }
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
            layoutNotice = "No room to move the other tiles out of the way. Free some space first."
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
            layoutNotice = "That size overlaps another tile. Make room before resizing."
            return
        }
        mutatePage(page) { l -> l.mapIndexed { i, t -> if (i == index) t.withCell(t.x, t.y, cw, ch) else t } }
    }

    /** Restores the layout from before the last add / move / resize / remove. */
    fun undo() {
        val previous = history.lastOrNull() ?: return
        history = history.dropLast(1)
        pages = previous
        DashboardStore.save(context, pages)
    }

    /** Clears one page (releasing any hosted app-widgets); Undo brings it back. */
    fun resetPage(page: Int) {
        pages.getOrNull(page)?.filterIsInstance<DashboardItem.SystemWidget>()
            ?.forEach { WidgetHostHolder.delete(context, it.appWidgetId) }
        mutatePage(page) { emptyList() }
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

    // Auto-connect to the saved OBD adapter when permission is held and we're
    // not already connected. Called on first launch and on every resume.
    val autoConnectObd: () -> Unit = {
        val state = ObdBluetoothManager.connectionState.value
        if (state == ObdConnectionState.DISCONNECTED || state == ObdConnectionState.ERROR) {
            val saved = ObdBluetoothManager.savedDeviceAddress()
            val missingPerms = requiredBluetoothPermissions().any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (saved != null && !missingPerms) scope.launch { ObdBluetoothManager.connect(saved) }
        }
    }

    // Observe media; re-check notification access on resume so granting it in
    // system settings takes effect without an app restart. Also auto-connect OBD.
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        ObdBluetoothManager.setContext(context)
        McuReader.setContext(context)
        mediaController.start()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasMediaAccess = CarMediaController.hasNotificationAccess(context)
                    if (hasMediaAccess) mediaController.start()
                    autoConnectObd()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mediaController.stop()
        }
    }

    // Keep the OBD link up while the dashboard is on screen: retry every 5s
    // whenever it's down. Paused (another app fullscreen) means no retries; the
    // resume observer above reconnects the moment we come back.
    LaunchedEffect(resumed) {
        while (resumed) {
            autoConnectObd()
            delay(5000)
        }
    }

    LaunchedEffect(obdConnection) {
        while (obdConnection == ObdConnectionState.CONNECTED) {
            ObdBluetoothManager.poll()
            delay(500)
        }
    }

    LaunchedEffect(Unit) { updateManager.checkForUpdate() }

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
        addTargetPage = page
        showAddMenu = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(dashBackground())
            // Android forces a transparent status bar whenever a floating
            // (freeform) window is on screen, e.g. a docked Maps window. Lay out
            // below it then; the inset is zero while the bar is hidden.
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        TopBar(
            currentPage = pagerState.currentPage,
            clock = clock,
            versionName = updateManager.currentVersionName,
            obdConnection = obdConnection,
            obdData = obdData,
            editing = editing,
            onApps = { showAllApps = true },
            onMaps = { SplitLauncher.launchSplit(context, "com.google.android.apps.maps") },
            onSplit = {
                if (SplitLauncher.isSystemSplitAvailable()) showSplitPicker = true
                else showSplitEnable = true
            },
            onToggleEdit = { editing = !editing },
            onTheme = { showThemePicker = true },
            onSystem = { showSystemDialog = true }
        )

        UpdateBanner(
            status = updateStatus,
            onUpdate = onUpdate,
            onDismiss = { updateManager.dismiss() }
        )

        if (editing && !inSplitMode) {
            EditBar(
                page = pagerState.currentPage,
                canUndo = history.isNotEmpty(),
                onAdd = { onAdd(pagerState.currentPage) },
                onUndo = { undo() },
                onReset = { resetPage(pagerState.currentPage) },
                onDone = { editing = false }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !blockPagerSwipe,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                DashboardPage(
                    pageItems = pages[page],
                    editing = editing,
                    inSplitMode = inSplitMode,
                    onModelTouch = { blockPagerSwipe = it },
                    appsByPackage = appsByPackage,
                    mediaState = mediaState,
                    mediaController = mediaController,
                    hasMediaAccess = hasMediaAccess,
                    obdData = obdData,
                    obdConnection = obdConnection,
                    onConnectObd = onConnectObd,
                    onPickDevice = onPickDevice,
                    onLaunchApp = onLaunchApp,
                    onLaunchSplitPair = onLaunchSplitPair,
                    onEditLaunchBar = { index -> launchBarEditor = page to index },
                    onRemove = { index -> removeAt(page, index) },
                    onMoveCell = { index, x, y -> moveCell(page, index, x, y) },
                    onResizeCell = { index, w, h -> resizeCell(page, index, w, h) },
                    canPlace = { index, x, y, w, h ->
                        DashboardStore.canPlace(pages[page], index, x, y, w, h)
                    },
                    canMove = { index, x, y ->
                        DashboardStore.moveResolving(pages[page], index, x, y) != null
                    },
                    onAdd = { onAdd(page) }
                )
            }

            // Floating swap button (bottom-centre), shown whenever the launcher
            // shares the screen — regardless of how the split was started (our
            // accessibility path or the OS's manual recents gesture). Swapping
            // needs the accessibility service; if it isn't on, tapping prompts to
            // enable it instead of silently doing nothing.
            if (inSplitMode) {
                FloatingActionButton(
                    onClick = {
                        if (SplitLauncher.isSystemSplitAvailable()) SplitLauncher.swapSplit()
                        else showSplitEnable = true
                    },
                    containerColor = DashColors.Accent,
                    contentColor = Color.Black,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .size(56.dp)
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = "Swap split apps left/right"
                    )
                }
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
                        onLaunch = { onLaunchApp(it.packageName) },
                        onClose = { showAllApps = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    )
                }
            }
        }

        PageDots(
            count = DashboardStore.PAGE_COUNT,
            current = pagerState.currentPage,
            onSelect = { scope.launch { pagerState.animateScrollToPage(it) } }
        )
    }

    launchBarEditor?.let { (page, index) ->
        val bar = pages.getOrNull(page)?.getOrNull(index) as? DashboardItem.LaunchBar
        if (bar == null) {
            launchBarEditor = null
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
            onDismissRequest = { layoutNotice = null },
            containerColor = DashColors.Card,
            title = { Text("Layout needs room", color = DashColors.TextPrimary) },
            text = { Text(notice, color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { layoutNotice = null }) {
                    Text("Got it", color = DashColors.Accent)
                }
            }
        )
    }

    if (showThemePicker) {
        DashThemePickerDialog(
            selected = themeMode,
            onSelect = {
                themeMode = it
                DashThemeStore.save(context, it)
            },
            onDismiss = { showThemePicker = false }
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
            onDismissRequest = { showAddMenu = false },
            containerColor = DashColors.Card,
            title = { Text("Add to dashboard", color = DashColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddChoiceRow(Icons.Filled.Apps, "Add app") {
                        showAddMenu = false
                        showAppPicker = true
                    }
                    AddChoiceRow(Icons.Filled.Splitscreen, "Add app pair (split)") {
                        showAddMenu = false
                        pairPrimaryPackage = null
                        showPairPrimaryPicker = true
                    }
                    AddChoiceRow(Icons.Filled.Widgets, "Add widget") {
                        showAddMenu = false
                        showWidgetMenu = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddMenu = false }) {
                    Text("Cancel", color = DashColors.Muted)
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

    if (showSplitPicker) {
        AppPickerDialog(
            apps = apps,
            title = "Split screen with…",
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
            title = "Split pair — first app (left)",
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
            title = "Split pair — second app (right)",
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
            onDismissRequest = { showSplitEnable = false },
            containerColor = DashColors.Card,
            title = { Text("Enable split screen", color = DashColors.TextPrimary) },
            text = {
                Text(
                    "This ROM blocks the usual split-screen APIs, so OpenAuto Dash " +
                        "uses the system's own split — the same one you get from recents. " +
                        "Turn on \"OpenAuto Dash\" under Settings → Accessibility once to allow it.",
                    color = DashColors.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSplitEnable = false
                    SplitLauncher.openAccessibilitySettings(context)
                }) {
                    Text("Open settings", color = DashColors.TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitEnable = false }) {
                    Text("Cancel", color = DashColors.Muted)
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
            onDismissRequest = { if (!systemBusy) showSystemDialog = false },
            containerColor = DashColors.Card,
            title = { Text("System app (advanced)", color = DashColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Install OpenAuto Dash as a privileged system app so it can embed " +
                            "the real Google Maps app — with navigation — in the Maps tile, " +
                            "like OEM car launchers. With Magisk it installs systemlessly (a " +
                            "Magisk module — works even though /system is full); otherwise it " +
                            "uses su, or the internal root ADB (:${AdbInstaller.DEFAULT_PORT}). " +
                            "Reboot afterwards to activate it.",
                        color = DashColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = when {
                            !rootChecked -> "Checking root…"
                            rootAvailable -> "Root (su) available ✓ — will use Magisk module if present"
                            else -> "No su — will try internal root ADB (:${AdbInstaller.DEFAULT_PORT})."
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
                        Text("Reboot now", color = DashColors.Accent)
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
                                    systemMessage = "Installed. Reboot to activate embedded Maps."
                                }.onFailure {
                                    systemMessage = "Failed: ${it.message}"
                                }
                            }
                        }
                    ) {
                        Text("Install as system app", color = DashColors.Accent)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!systemBusy) showSystemDialog = false }) {
                    Text("Close", color = DashColors.Muted)
                }
            }
        )
    }
}
