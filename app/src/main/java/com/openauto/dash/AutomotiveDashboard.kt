@file:OptIn(ExperimentalFoundationApi::class)

package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SPEED_WARNING_KMH = 110

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

    val apps = remember { AppLauncher.loadApps(context) }
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }

    var pages by remember { mutableStateOf(DashboardStore.load(context)) }
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

    // Auto-install to /system/priv-app on first launch (internal root ADB, then
    // su), so the Maps tile can embed the real Google Maps app after a reboot.
    // Runs once (guarded by a flag) — never re-remounts /system on later boots.
    LaunchedEffect(Unit) {
        if (SystemInstaller.isSystemApp(context)) return@LaunchedEffect
        val prefs = context.getSharedPreferences("system_install_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("attempted", false)) return@LaunchedEffect
        systemBusy = true
        val res = withContext(Dispatchers.IO) { SystemInstaller.install(context) }
        systemBusy = false
        // Attempt once; the manual dialog button remains for explicit retries.
        prefs.edit().putBoolean("attempted", true).apply()
        res.onSuccess {
            systemInstalled = true
            systemMessage = "Installed to /system/priv-app. Reboot to activate embedded Google Maps."
            showSystemDialog = true
        }.onFailure {
            systemMessage = "Auto system-install failed: ${it.message}"
        }
    }

    fun mutatePage(page: Int, transform: (List<DashboardItem>) -> List<DashboardItem>) {
        pages = pages.mapIndexed { i, list -> if (i == page) transform(list) else list }
        DashboardStore.save(context, pages)
    }

    /** Add only when the tile fits; a full page must never create a hidden overlap. */
    fun addItem(page: Int, item: DashboardItem): Boolean {
        val list = pages.getOrNull(page) ?: return false
        val cell = DashboardStore.firstFreeCell(list, item.w, item.h)
        if (cell == null) {
            layoutNotice = "This dashboard has no space for that tile. Remove, resize, or use another page."
            return false
        }
        mutatePage(page) { it + item.withCell(cell.first, cell.second, item.w, item.h) }
        return true
    }

    fun removeAt(page: Int, index: Int) {
        val item = pages.getOrNull(page)?.getOrNull(index) ?: return
        if (item is DashboardItem.SystemWidget) WidgetHostHolder.delete(context, item.appWidgetId)
        mutatePage(page) { list -> list.filterIndexed { i, _ -> i != index } }
    }

    // Move a tile's top-left to grid cell (x, y), keeping its span (drag-to-place).
    fun moveCell(page: Int, index: Int, x: Int, y: Int) {
        mutatePage(page) { list ->
            val item = list.getOrNull(index) ?: return@mutatePage list
            if (!DashboardStore.canPlace(list, index, x, y, item.w, item.h)) {
                layoutNotice = "That area is already occupied. Choose a highlighted free space."
                list
            } else {
                list.mapIndexed { i, it -> if (i == index) it.withCell(x, y, it.w, it.h) else it }
            }
        }
    }

    // Resize a tile to span w x h cells, keeping its top-left. Width/height are
    // capped at the grid edge from the tile's position so it grows in place
    // instead of being shoved left/up to make a too-big span fit.
    fun resizeCell(page: Int, index: Int, w: Int, h: Int) {
        mutatePage(page) { list ->
            if (index !in list.indices) list
            else list.mapIndexed { i, it ->
                if (i != index) it
                else {
                    val cw = w.coerceIn(it.minW(), GRID_COLS - it.x)
                    val ch = h.coerceIn(it.minH(), GRID_ROWS - it.y)
                    if (!DashboardStore.canPlace(list, index, it.x, it.y, cw, ch)) {
                        layoutNotice = "That size overlaps another tile. Make room before resizing."
                        it
                    } else it.withCell(it.x, it.y, cw, ch)
                }
            }
        }
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
            delay(1000)
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
    DisposableEffect(lifecycleOwner) {
        ObdBluetoothManager.setContext(context)
        McuReader.setContext(context)
        mediaController.start()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMediaAccess = CarMediaController.hasNotificationAccess(context)
                if (hasMediaAccess) mediaController.start()
                autoConnectObd()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mediaController.stop()
        }
    }

    // Keep the OBD link up "all the time": retry every 5s whenever it's down.
    LaunchedEffect(Unit) {
        while (true) {
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
                    onRemove = { index -> removeAt(page, index) },
                    onMoveCell = { index, x, y -> moveCell(page, index, x, y) },
                    onResizeCell = { index, w, h -> resizeCell(page, index, w, h) },
                    canPlace = { index, x, y, w, h ->
                        DashboardStore.canPlace(pages[page], index, x, y, w, h)
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

        PageDots(
            count = DashboardStore.PAGE_COUNT,
            current = pagerState.currentPage,
            onSelect = { scope.launch { pagerState.animateScrollToPage(it) } }
        )
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
        AlertDialog(
            onDismissRequest = { showWidgetMenu = false },
            containerColor = DashColors.Card,
            title = { Text("Add widget", color = DashColors.TextPrimary) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AddChoiceRow(Icons.Filled.Navigation, BuiltinKind.NAVMAP.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.NAVMAP))
                    }
                    AddChoiceRow(Icons.Filled.Directions, BuiltinKind.NAVIGATION.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, w = 4, h = 3))
                    }
                    AddChoiceRow(Icons.Filled.MusicNote, BuiltinKind.MEDIA.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.MEDIA))
                    }
                    AddChoiceRow(Icons.Filled.Speed, BuiltinKind.TELEMETRY.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.TELEMETRY))
                    }
                    AddChoiceRow(Icons.Filled.Warning, BuiltinKind.OBD_DTC.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.OBD_DTC))
                    }
                    AddChoiceRow(Icons.Filled.Speed, BuiltinKind.OBD_ALL.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.OBD_ALL))
                    }
                    AddChoiceRow(Icons.Filled.LocalGasStation, BuiltinKind.RANGE.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.RANGE))
                    }
                    AddChoiceRow(Icons.Filled.DirectionsCar, BuiltinKind.CAR3D.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.CAR3D))
                    }
                    AddChoiceRow(Icons.Filled.SensorDoor, BuiltinKind.DOORS.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.DOORS))
                    }
                    AddChoiceRow(Icons.Filled.Sensors, BuiltinKind.CAN_MON.label) {
                        showWidgetMenu = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(BuiltinKind.CAN_MON))
                    }
                    AddChoiceRow(Icons.Filled.Widgets, "System widget…") {
                        showWidgetMenu = false
                        addSystemWidget.pickFromList()
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showWidgetMenu = false }) {
                    Text("Cancel", color = DashColors.Muted)
                }
            }
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

// --- Top bar & page dots -----------------------------------------------------

@Composable
private fun TopBar(
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
private fun StatusChip(
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

@Composable
private fun PageDots(count: Int, current: Int, onSelect: (Int) -> Unit) {
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

// --- A single dashboard page -------------------------------------------------

/** Natural height of a full widget tile stacked in the scrollable split-screen column. */
private val SPLIT_WIDGET_HEIGHT = 300.dp

private data class GridPreview(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val isValid: Boolean
)

@Composable
private fun DashboardPage(
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
    onRemove: (Int) -> Unit,
    onMoveCell: (Int, Int, Int) -> Unit,
    onResizeCell: (Int, Int, Int) -> Unit,
    canPlace: (Int, Int, Int, Int, Int) -> Boolean,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Renders one tile's inner content with all the shared dependencies wired in.
    val tileContent: @Composable (DashboardItem) -> Unit = { item ->
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
            onModelTouch = onModelTouch
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
            pageItems.forEach { item ->
                val h = if (item.isCompactTile()) 96.dp else SPLIT_WIDGET_HEIGHT
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(h)
                        .clip(RoundedCornerShape(20.dp))
                        .background(DashColors.Bar)
                ) { tileContent(item) }
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

        pageItems.forEachIndexed { index, item ->
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
                onRemove = onRemove,
                onPreview = { x, y, w, h, isValid -> preview = GridPreview(x, y, w, h, isValid) },
                onPreviewClear = { preview = null },
                content = { tileContent(item) }
            )
        }

        // "+" to add a tile (placed at the first free cell by the caller).
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
            AddTile(onClick = onAdd)
        }
    }
}

/**
 * One tile placed on the dashboard grid. Fixed at its cell rectangle normally;
 * in edit mode it can be long-press-dragged to another cell (snapping on drop),
 * resized by the bottom-right handle, or removed.
 */
@Composable
private fun GridTile(
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
        Box(modifier = Modifier.fillMaxSize()) { content() }

        if (editing) {
            // Transparent scrim over the content captures the long-press drag so
            // even map / widget tiles (whose content eats touches) can be moved,
            // and taps don't reach the content. The buttons below sit above it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                active = true; onModelTouch(true)
                                onPreview(snapX(), snapY(), item.w, item.h, canPlace(index, snapX(), snapY(), item.w, item.h))
                            },
                            onDrag = { change, delta ->
                                change.consume(); dragOffset += delta
                                onPreview(snapX(), snapY(), item.w, item.h, canPlace(index, snapX(), snapY(), item.w, item.h))
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

            FilledIconButton(
                onClick = { onRemove(index) },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(30.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = DashColors.Warning, contentColor = Color.Black
                )
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }

            // Bottom-right resize handle: drag to change the cell span.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(DashColors.Accent.copy(alpha = 0.85f))
                    .pointerInput(index) {
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
                    contentDescription = "Resize",
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
private fun TileContent(
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
    onModelTouch: (Boolean) -> Unit
) {
    when (item) {
        is DashboardItem.AppShortcut -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AppShortcutTile(
                app = appsByPackage[item.packageName],
                packageName = item.packageName,
                editing = editing,
                onClick = { onLaunchApp(item.packageName) }
            )
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
            BuiltinKind.NAVMAP -> Box(
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
            BuiltinKind.NAVIGATION -> DirectionsCard(
                hasAccess = hasMediaAccess,
                context = context,
                modifier = Modifier.fillMaxSize()
            )
            BuiltinKind.MEDIA -> MediaCard(
                mediaState = mediaState,
                controller = mediaController,
                hasAccess = hasMediaAccess,
                context = context,
                modifier = Modifier.fillMaxSize()
            )
            BuiltinKind.TELEMETRY -> ObdCard(
                obdData = obdData,
                connection = obdConnection,
                onConnect = onConnectObd,
                onPickDevice = onPickDevice,
                modifier = Modifier.fillMaxSize()
            )
            BuiltinKind.OBD_DTC -> ObdDtcCard(
                connection = obdConnection,
                onConnect = onConnectObd,
                modifier = Modifier.fillMaxSize()
            )
            BuiltinKind.OBD_ALL -> ObdAllCard(
                obdData = obdData,
                connection = obdConnection,
                onConnect = onConnectObd,
                modifier = Modifier.fillMaxSize()
            )
            BuiltinKind.RANGE -> RangeCard(
                obdData = obdData,
                connection = obdConnection,
                onConnect = onConnectObd,
                modifier = Modifier.fillMaxSize()
            )
            BuiltinKind.DOORS -> DoorsCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CAN_MON -> CanMonitorCard(modifier = Modifier.fillMaxSize())
            BuiltinKind.CAR3D -> Box(
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

        is DashboardItem.SystemWidget -> Card(
            modifier = Modifier.fillMaxSize()
        ) {
            HostedSystemWidget(appWidgetId = item.appWidgetId, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AppShortcutTile(
    app: AppEntry?,
    packageName: String,
    editing: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            // Disable launching while editing so the tile's long-press starts a
            // drag (to reorder / stack) instead of opening the app.
            .clickable(enabled = !editing, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(DashColors.CardHi)
                .border(1.dp, DashColors.Line, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (app != null) {
                AppIcon(icon = app.icon, size = 44.dp)
            } else {
                Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app?.label ?: packageName.substringAfterLast('.'),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SplitPairTile(
    primaryApp: AppEntry?,
    secondaryApp: AppEntry?,
    primaryPackage: String,
    secondaryPackage: String,
    editing: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            // Disabled while editing so a long-press starts a drag, not a launch.
            .clickable(enabled = !editing, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .height(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(DashColors.CardHi)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                PairIcon(primaryApp)
                Icon(
                    Icons.Filled.Splitscreen,
                    contentDescription = null,
                    tint = DashColors.Accent,
                    modifier = Modifier.size(16.dp)
                )
                PairIcon(secondaryApp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${primaryApp?.label ?: primaryPackage.substringAfterLast('.')} | " +
                (secondaryApp?.label ?: secondaryPackage.substringAfterLast('.')),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PairIcon(app: AppEntry?) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(DashColors.Card),
        contentAlignment = Alignment.Center
    ) {
        if (app != null) {
            AppIcon(icon = app.icon, size = 26.dp)
        } else {
            Icon(
                Icons.Filled.Apps,
                contentDescription = null,
                tint = DashColors.Muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
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
                .background(DashColors.Card),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add", tint = DashColors.Accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("Add", color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AddChoiceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(DashColors.CardHi)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
    }
}

// --- Add-app picker ----------------------------------------------------------

@Composable
private fun AppPickerDialog(
    apps: List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Choose an app"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(title, color = DashColors.TextPrimary) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 84.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppShortcutTile(app = app, packageName = app.packageName, onClick = { onPick(app) })
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = DashColors.Muted)
            }
        }
    )
}

// --- Content: banner, map, cards, drawer -------------------------------------

@Composable
private fun UpdateBanner(
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

@Composable
private fun MediaCard(
    mediaState: MediaState,
    controller: CarMediaController,
    hasAccess: Boolean,
    context: Context,
    modifier: Modifier = Modifier
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mediaState.isPlaying, mediaState.title, mediaState.durationMs) {
        while (true) {
            positionMs = controller.positionMs()
            delay(500)
        }
    }
    val fraction = if (mediaState.durationMs > 0L) {
        (positionMs.toFloat() / mediaState.durationMs).coerceIn(0f, 1f)
    } else 0f

    val art = mediaState.artwork
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val glow = DashColors.Glow
    // The cover's dominant colour bleeds out beneath it, like light off a screen.
    val bleed = remember(art, accent) { art?.averageColor() ?: accent }
    val artShape = RoundedCornerShape(20.dp)

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .drawBehind {
                            val c = Offset(size.width * 0.5f, size.height * 0.8f)
                            val r = size.maxDimension * 1.05f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(bleed.copy(alpha = 0.25f + 0.35f * glow), Color.Transparent),
                                    center = c, radius = r
                                ),
                                radius = r, center = c
                            )
                        }
                        .clip(artShape)
                        .background(Brush.linearGradient(listOf(accent, accent2)))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), artShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (art != null) {
                        Image(
                            bitmap = art.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    // Glossy sheen over the top-left corner.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                                    start = Offset.Zero,
                                    end = Offset(240f, 240f)
                                )
                            )
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mediaState.isPlaying) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(DashColors.Good)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = "NOW PLAYING",
                            color = DashColors.Accent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            mediaState.hasMedia && mediaState.title.isNotBlank() -> mediaState.title
                            hasAccess -> "Nothing playing"
                            else -> "Media access needed"
                        },
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = mediaState.artist.ifBlank { if (hasAccess) "\u2014" else "Tap to enable" },
                        color = DashColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (hasAccess) {
                if (mediaState.durationMs > 0L) {
                    Spacer(Modifier.height(10.dp))
                    MediaProgress(fraction = fraction)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(positionMs), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                        Text(formatTime(mediaState.durationMs), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassRoundButton(
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        size = 52.dp,
                        iconSize = 30.dp,
                        onClick = { controller.previous() }
                    )
                    Spacer(Modifier.width(18.dp))
                    GradientRoundButton(
                        icon = if (mediaState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        size = 70.dp,
                        iconSize = 38.dp,
                        onClick = { controller.playPause() }
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassRoundButton(
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        size = 52.dp,
                        iconSize = 30.dp,
                        onClick = { controller.next() }
                    )
                }
            } else {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { CarMediaController.openNotificationAccessSettings(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DashColors.Accent,
                        contentColor = DashColors.OnAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Media Access")
                }
            }
        }
    }
}

/** Seek bar: recessed track, accent-gradient fill with a glow, bright knob at the playhead. */
@Composable
private fun MediaProgress(fraction: Float, modifier: Modifier = Modifier) {
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val glow = DashColors.Glow
    val track = if (DashColors.Glass) Color.Black.copy(alpha = 0.35f) else DashColors.CardHi
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
    ) {
        val h = 5.dp.toPx()
        val y = size.height / 2f
        val r = h / 2f
        drawRoundRect(
            color = track,
            topLeft = Offset(0f, y - r),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(r)
        )
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) {
            if (glow > 0f) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.30f * glow),
                    topLeft = Offset(0f, y - h),
                    size = Size(w, h * 2f),
                    cornerRadius = CornerRadius(h)
                )
            }
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(accent, accent2), endX = size.width),
                topLeft = Offset(0f, y - r),
                size = Size(w, h),
                cornerRadius = CornerRadius(r)
            )
        }
        drawCircle(color = accent.copy(alpha = 0.35f), radius = 8.dp.toPx(), center = Offset(w, y))
        drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(w, y))
    }
}

/** Secondary round control: frosted disc with a hairline rim. */
@Composable
private fun GlassRoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val fill: Brush = if (DashColors.Glass) {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f)))
    } else SolidColor(DashColors.CardHi)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, DashColors.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = DashColors.TextPrimary, modifier = Modifier.size(iconSize))
    }
}

/** Primary round control: accent-gradient disc with a halo that scales with the theme's glow. */
@Composable
private fun GradientRoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val accent = DashColors.Accent
    val glow = DashColors.Glow
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                if (glow > 0f) {
                    val r = this.size.minDimension * 0.85f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.45f * glow), Color.Transparent),
                            center = center, radius = r
                        ),
                        radius = r
                    )
                }
            }
            .clip(CircleShape)
            .background(DashColors.AccentBrush)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = DashColors.OnAccent, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun ObdCard(
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    onPickDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = connection == ObdConnectionState.CONNECTED
    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            // Short tiles drop the secondary chips; tall tiles stack the RPM bar
            // and chips under the gauge instead of beside it.
            val compact = maxHeight < 250.dp
            val stacked = maxWidth < maxHeight * 1.15f

            Column(modifier = Modifier.fillMaxSize()) {
                // Header: title + live connection status / connect button.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "TELEMETRY",
                        color = DashColors.Accent,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (connected) {
                        TextButton(onClick = onPickDevice, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(DashColors.Good)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Live", color = DashColors.Good, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Button(
                            onClick = onConnect,
                            enabled = connection != ObdConnectionState.CONNECTING,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DashColors.Accent,
                                contentColor = DashColors.OnAccent
                            ),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Bluetooth, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (connection == ObdConnectionState.CONNECTING) "\u2026" else "Connect")
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                val gauge: @Composable (Modifier) -> Unit = { m ->
                    AnalogGauge(
                        value = if (connected) obdData.speedKmh.toFloat() else 0f,
                        maxValue = 220f,
                        valueText = if (connected) obdData.speedKmh.toString() else "--",
                        label = "SPEED",
                        unit = "km/h",
                        accent = DashColors.Speed,
                        redlineAccent = DashColors.Warning,
                        redlineFraction = SPEED_WARNING_KMH / 220f,
                        dimmed = !connected,
                        majorTicks = 12,
                        hero = true,
                        modifier = m
                    )
                }
                val rpm: @Composable (Modifier) -> Unit = { m ->
                    RpmBar(rpm = obdData.rpm, maxRpm = 7000f, dimmed = !connected, modifier = m)
                }
                val chips: @Composable (Modifier) -> Unit = { m ->
                    MeterChip(
                        label = "Coolant",
                        valueText = if (connected) "${obdData.coolantTempC}\u00b0" else "--",
                        fraction = (obdData.coolantTempC / 120f),
                        color = coolantColor(obdData.coolantTempC),
                        dimmed = !connected,
                        modifier = m
                    )
                    MeterChip(
                        label = "Load",
                        valueText = if (connected) "${obdData.engineLoadPct}%" else "--",
                        fraction = obdData.engineLoadPct / 100f,
                        color = DashColors.Accent,
                        dimmed = !connected,
                        modifier = m
                    )
                    MeterChip(
                        label = "Battery",
                        valueText = if (connected) "%.1fV".format(obdData.voltage) else "--",
                        fraction = ((obdData.voltage - 11.0) / 4.0).toFloat(),
                        color = if (obdData.voltage in 12.0..15.0) DashColors.Good else DashColors.Warning,
                        dimmed = !connected,
                        modifier = m
                    )
                }

                if (stacked) {
                    gauge(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    rpm(Modifier.fillMaxWidth())
                    if (!compact) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) { chips(Modifier.weight(1f)) }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        gauge(Modifier.weight(1.25f).fillMaxHeight())
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                        ) {
                            rpm(Modifier.fillMaxWidth())
                            if (!compact) chips(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/**
 * Horizontal rev bar: green through the accent into red at the redline, with a
 * glow underlay on glowing themes and a marker at the redline.
 */
@Composable
private fun RpmBar(
    rpm: Int,
    maxRpm: Float,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    redlineFraction: Float = 0.82f
) {
    val frac by animateFloatAsState(
        targetValue = if (dimmed) 0f else (rpm / maxRpm).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "rpmBar"
    )
    val good = DashColors.Good
    val accent = DashColors.Accent
    val warning = DashColors.Warning
    val glow = DashColors.Glow
    val track = if (DashColors.Glass) Color.Black.copy(alpha = 0.35f) else DashColors.Background
    val overRedline = frac >= redlineFraction

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RPM", color = DashColors.TextSecondary, letterSpacing = 1.5.sp, style = MaterialTheme.typography.labelSmall)
            Text(
                text = if (dimmed) "--" else rpm.toString(),
                color = if (dimmed) DashColors.Muted else if (overRedline) warning else DashColors.Rpm,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            val h = size.height
            val corner = CornerRadius(h / 2f)
            drawRoundRect(color = track, size = size, cornerRadius = corner)
            val w = size.width * frac
            if (w > 0f) {
                val fill = Brush.horizontalGradient(listOf(good, accent, warning), endX = size.width)
                if (glow > 0f) {
                    drawRoundRect(
                        brush = fill,
                        topLeft = Offset(0f, -h * 0.6f),
                        size = Size(w, h * 2.2f),
                        cornerRadius = CornerRadius(h),
                        alpha = 0.30f * glow
                    )
                }
                drawRoundRect(brush = fill, size = Size(w, h), cornerRadius = corner)
            }
            // Redline marker.
            val rx = size.width * redlineFraction
            drawLine(
                color = warning.copy(alpha = 0.7f),
                start = Offset(rx, -2f),
                end = Offset(rx, h + 2f),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
    }
}

// --- Directions (Google Maps / Waze next turn) --------------------------------

private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

/** Opens the navigation app that is driving [nav] (or Google Maps) beside the dashboard. */
private fun openNavigationApp(context: Context, nav: NavState) {
    SplitLauncher.launchSplit(context, nav.packageName.ifEmpty { GOOGLE_MAPS_PACKAGE })
}

/**
 * Dashboard tile showing the next manoeuvre from Google Maps / Waze: the turn
 * glyph on a glowing disc, the distance in hero numerals, the street, and the
 * ETA line as chips. Tapping it brings the navigation app up beside the
 * dashboard. Needs the same Notification access grant as the music player.
 */
@Composable
private fun DirectionsCard(
    hasAccess: Boolean,
    context: Context,
    modifier: Modifier = Modifier
) {
    val nav by NavDirections.state.collectAsState()
    val glow = DashColors.Glow
    val accent = DashColors.Accent

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    if (hasAccess) openNavigationApp(context, nav)
                    else CarMediaController.openNotificationAccessSettings(context)
                }
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "DIRECTIONS",
                    color = DashColors.Accent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (nav.active) DashColors.Good else DashColors.Muted)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            nav.active && nav.packageName == "com.waze" -> "Waze"
                            nav.active -> "Google Maps"
                            else -> "No route"
                        },
                        color = if (nav.active) DashColors.Good else DashColors.Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            when {
                !hasAccess -> DirectionsEmpty(
                    icon = Icons.Filled.Directions,
                    title = "Notification access needed",
                    hint = "Directions come from Google Maps' navigation notification.",
                    action = "Grant access",
                    onAction = { CarMediaController.openNotificationAccessSettings(context) }
                )
                !nav.active -> DirectionsEmpty(
                    icon = Icons.Filled.Navigation,
                    title = "No active route",
                    hint = "Start navigation in Google Maps or Waze and the next turn shows here.",
                    action = "Open Google Maps",
                    onAction = { openNavigationApp(context, nav) }
                )
                else -> {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ManeuverIcon(nav = nav, size = 84.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val (value, unit) = nav.distanceParts
                            if (value.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = value,
                                        color = if (glow > 0f) Color.Unspecified else DashColors.TextPrimary,
                                        fontSize = 44.sp,
                                        lineHeight = 44.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.05).em,
                                        maxLines = 1,
                                        style = TextStyle(
                                            brush = if (glow > 0f) Brush.verticalGradient(
                                                listOf(Color.White, lerp(Color.White, accent, 0.45f))
                                            ) else null,
                                            shadow = if (glow > 0f) Shadow(accent.copy(alpha = 0.8f * glow), blurRadius = 30f) else null
                                        )
                                    )
                                    if (unit.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = unit.uppercase(),
                                            color = DashColors.TextSecondary,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.2.em,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = nav.instruction,
                                color = DashColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    val chips = nav.etaParts
                    if (chips.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            chips.take(3).forEach { InfoPill(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionsEmpty(
    icon: ImageVector,
    title: String,
    hint: String,
    action: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            color = DashColors.TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) { Text(action) }
    }
}

/** The manoeuvre glyph from the notification on a glowing accent disc. */
@Composable
private fun ManeuverIcon(nav: NavState, size: Dp) {
    val accent = DashColors.Accent
    val glow = DashColors.Glow
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                if (glow > 0f) {
                    val r = this.size.minDimension * 0.85f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.45f * glow), Color.Transparent),
                            center = center, radius = r
                        ),
                        radius = r
                    )
                }
            }
            .clip(CircleShape)
            .background(DashColors.AccentBrush)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.62f)
            )
        } else {
            Icon(
                Icons.Filled.Directions,
                contentDescription = null,
                tint = DashColors.OnAccent,
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}

/** Small glass pill for an ETA segment ("12 min", "6.4 km", "09:48"). */
@Composable
private fun InfoPill(text: String) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text = text,
        color = DashColors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(shape)
            .background(if (DashColors.Glass) Color.White.copy(alpha = 0.08f) else DashColors.CardHi)
            .border(1.dp, DashColors.Line, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * Compact next-turn strip floated over the MapLibre map tile: glyph, distance
 * and street on one line, ETA underneath. Tap to bring the navigation app up.
 */
@Composable
private fun DirectionsBanner(nav: NavState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .then(if (DashColors.Glass) glassPanel(shape) else Modifier.clip(shape).background(DashColors.Card.copy(alpha = 0.92f)).border(1.dp, DashColors.Line, shape))
            .clickable { openNavigationApp(context, nav) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ManeuverIcon(nav = nav, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                if (nav.distance.isNotEmpty()) {
                    Text(
                        text = nav.distance,
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = nav.instruction,
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            if (nav.eta.isNotEmpty()) {
                Text(nav.eta, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

private fun coolantColor(tempC: Int): Color = when {
    tempC >= 105 -> DashColors.Warning
    tempC >= 75 -> DashColors.Good
    else -> DashColors.Speed
}

/**
 * A racing-style analog gauge: a 270° dark dial with tick marks, a coloured
 * sweep arc (turning red past [redlineFraction]), an animated needle and a big
 * digital readout in the middle. Scales to whatever size the tile gives it.
 */
@Composable
private fun AnalogGauge(
    value: Float,
    maxValue: Float,
    valueText: String,
    label: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
    redlineAccent: Color = DashColors.Warning,
    redlineFraction: Float = 0.8f,
    dimmed: Boolean = false,
    majorTicks: Int = 9,
    // Hero style (telemetry speed): tick labels, glowing tip dot instead of a
    // needle, and large gradient numerals - the mockup's instrument cluster.
    hero: Boolean = false
) {
    val target = (value / maxValue).coerceIn(0f, 1f)
    val frac by animateFloatAsState(
        targetValue = if (dimmed) 0f else target,
        animationSpec = tween(durationMillis = 500),
        label = "gauge"
    )
    val startAngle = 135f      // 7:30 position (Compose: 0° = 3 o'clock, CW positive)
    val sweepTotal = 270f
    val sweepColor = if (frac >= redlineFraction) redlineAccent else accent
    val needleColor = if (dimmed) DashColors.Muted else sweepColor
    val glass = DashColors.Glass
    val glow = DashColors.Glow
    val accent2 = DashColors.Accent2

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val gaugePx = min(maxWidth.value, maxHeight.value)
        val valueSize = if (hero) (gaugePx * 0.30f).coerceIn(22f, 76f).sp else (gaugePx * 0.20f).coerceIn(16f, 46f).sp
        val unitSize = (gaugePx * 0.075f).coerceIn(8f, 14f).sp
        val labelSize = (gaugePx * 0.085f).coerceIn(9f, 15f).sp
        val tickLabelSize = (gaugePx * 0.05f).coerceIn(7f, 12f).sp
        val textMeasurer = rememberTextMeasurer()
        val tickLabelColor = DashColors.TextSecondary.copy(alpha = 0.55f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.085f
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // Base track.
            drawArc(
                color = if (glass) Color.White.copy(alpha = 0.07f) else DashColors.CardHi,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Dim redline zone on the track.
            drawArc(
                color = redlineAccent.copy(alpha = 0.35f),
                startAngle = startAngle + sweepTotal * redlineFraction,
                sweepAngle = sweepTotal * (1f - redlineFraction),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Active sweep: accent->accent2 gradient along the arc, a wide soft
            // halo underneath (scaled by the theme's glow) and a bright core line.
            if (frac > 0f) {
                val sweepBrush: Brush = if (frac >= redlineFraction) SolidColor(redlineAccent)
                    else gaugeSweepBrush(center, accent, accent2)
                if (glow > 0f) {
                    drawArc(
                        brush = sweepBrush,
                        startAngle = startAngle,
                        sweepAngle = sweepTotal * frac,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        alpha = 0.14f * glow,
                        style = Stroke(width = stroke * 3.2f, cap = StrokeCap.Round)
                    )
                }
                drawArc(
                    brush = sweepBrush,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    alpha = 0.25f + 0.15f * glow,
                    style = Stroke(width = stroke * 1.9f, cap = StrokeCap.Round)
                )
                drawArc(
                    brush = sweepBrush,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.35f + 0.3f * glow),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 0.22f, cap = StrokeCap.Round)
                )
            }
            // Tick marks (hero adds minor ticks between the majors, plus labels).
            val tickOuter = radius - stroke * 0.6f
            val tickInner = radius - stroke * 1.5f
            val minorPerMajor = if (hero) 4 else 1
            val tickCount = (majorTicks - 1) * minorPerMajor
            for (i in 0..tickCount) {
                val major = i % minorPerMajor == 0
                val a = Math.toRadians((startAngle + sweepTotal * i / tickCount).toDouble())
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val inner = if (major) tickInner else tickInner + (tickOuter - tickInner) * 0.45f
                drawLine(
                    color = DashColors.TextSecondary.copy(alpha = if (major) 0.6f else 0.25f),
                    start = Offset(center.x + ca * inner, center.y + sa * inner),
                    end = Offset(center.x + ca * tickOuter, center.y + sa * tickOuter),
                    strokeWidth = stroke * if (major) 0.16f else 0.09f,
                    cap = StrokeCap.Round
                )
                // Tile-sized gauges label every other major and skip the two end
                // labels, which would collide with the unit text below the numerals.
                val majorIndex = i / minorPerMajor
                val showLabel = hero && major && (
                    gaugePx >= 300f || (majorIndex % 2 == 0 && i != 0 && i != tickCount)
                )
                if (showLabel) {
                    val labelValue = (maxValue * i / tickCount).roundToInt().toString()
                    val layout = textMeasurer.measure(
                        labelValue,
                        style = TextStyle(fontSize = tickLabelSize, fontWeight = FontWeight.SemiBold, color = tickLabelColor)
                    )
                    val lr = tickInner - stroke * 0.55f - maxOf(layout.size.width, layout.size.height) * 0.5f
                    drawText(
                        layout,
                        topLeft = Offset(
                            center.x + ca * lr - layout.size.width / 2f,
                            center.y + sa * lr - layout.size.height / 2f
                        )
                    )
                }
            }
            if (hero) {
                // Glowing tip dot at the end of the sweep.
                if (!dimmed) {
                    val tipA = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
                    val tip = Offset(center.x + cos(tipA).toFloat() * radius, center.y + sin(tipA).toFloat() * radius)
                    if (glow > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.9f * glow), Color.Transparent),
                                center = tip, radius = stroke * 1.6f
                            ),
                            radius = stroke * 1.6f, center = tip
                        )
                    }
                    drawCircle(color = Color.White, radius = stroke * 0.42f, center = tip)
                }
            } else {
                // Needle + hub.
                val needleA = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
                val nx = cos(needleA).toFloat()
                val ny = sin(needleA).toFloat()
                val needleLen = radius - stroke * 0.4f
                drawLine(
                    color = needleColor,
                    start = Offset(center.x - nx * radius * 0.12f, center.y - ny * radius * 0.12f),
                    end = Offset(center.x + nx * needleLen, center.y + ny * needleLen),
                    strokeWidth = stroke * 0.35f,
                    cap = StrokeCap.Round
                )
                drawCircle(color = DashColors.Card, radius = stroke * 0.9f, center = center)
                drawCircle(color = needleColor, radius = stroke * 0.5f, center = center)
            }
        }

        // Digital readout in the middle.
        val lit = glow > 0f && !dimmed
        // Hero numerals fade from white into the accent, like the mockup.
        val numeralBrush: Brush? = if (hero && lit) {
            Brush.verticalGradient(listOf(Color.White, lerp(Color.White, accent, 0.45f)))
        } else null
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = gaugePx.times(if (hero) 0.02f else 0.10f).dp)
        ) {
            Text(
                text = valueText,
                color = if (numeralBrush != null) Color.Unspecified else if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontSize = valueSize,
                fontWeight = if (hero) FontWeight.ExtraBold else FontWeight.Bold,
                letterSpacing = if (hero) (-0.06).em else (-0.04).em,
                maxLines = 1,
                // Numerals glow in the accent colour on glowing themes.
                style = TextStyle(
                    brush = numeralBrush,
                    shadow = if (lit) {
                        Shadow(color = accent.copy(alpha = 0.85f * glow), blurRadius = valueSize.value * 0.8f)
                    } else null
                )
            )
            Text(
                text = if (hero) unit.uppercase() else unit,
                color = if (hero) DashColors.TextSecondary else DashColors.Muted,
                fontSize = unitSize,
                fontWeight = if (hero) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = if (hero) 0.3.em else 0.15.em
            )
            if (!hero) {
                Spacer(Modifier.height(2.dp))
                Text(text = label, color = accent, fontSize = labelSize, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Small labelled meter: value on top, a rounded progress track below. */
@Composable
private fun MeterChip(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    val glass = DashColors.Glass
    val chipShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(chipShape)
            .background(if (glass) Color.White.copy(alpha = 0.06f) else DashColors.CardHi)
            .border(1.dp, DashColors.Line, chipShape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(
                valueText,
                color = if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(if (glass) Color.Black.copy(alpha = 0.35f) else DashColors.Background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (dimmed) 0f else fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(color, lerp(color, Color.White, 0.3f))))
            )
        }
    }
}

/** Reads and clears OBD Diagnostic Trouble Codes (fault codes). */
@Composable
private fun ObdDtcCard(
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val connected = connection == ObdConnectionState.CONNECTED
    var codes by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("FAULT CODES", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))

            if (!connected) {
                Text("OBD not connected", color = DashColors.Muted)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.Background)
                ) { Text("Connect") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true; message = null
                            scope.launch {
                                val r = ObdBluetoothManager.readTroubleCodes()
                                busy = false
                                r.onSuccess { codes = it; message = if (it.isEmpty()) "No fault codes ✓" else null }
                                    .onFailure { message = it.message ?: "Scan failed" }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.Background)
                    ) { Text("Scan") }
                    Button(
                        enabled = !busy && codes?.isNotEmpty() == true,
                        onClick = {
                            busy = true; message = null
                            scope.launch {
                                val res = ObdBluetoothManager.clearTroubleCodes()
                                busy = false
                                res.onSuccess { message = "Cleared ✓"; codes = emptyList() }
                                    .onFailure { message = it.message ?: "Clear failed" }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text("Clear") }
                }
                Spacer(Modifier.height(10.dp))
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DashColors.Accent)
                message?.let {
                    Text(it, color = if (it.contains("fail", true)) DashColors.Warning else DashColors.Good)
                    Spacer(Modifier.height(6.dp))
                }
                val list = codes
                if (list != null && list.isNotEmpty()) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        list.forEach { code ->
                            val dtc = ObdCodes.describe(code)
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text(
                                    "${dtc.code} — ${dtc.title}",
                                    color = DashColors.Warning,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    dtc.fix,
                                    color = DashColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shows every OBD value currently available. */
@Composable
private fun ObdAllCard(
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = connection == ObdConnectionState.CONNECTED
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("OBD DATA", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            if (!connected) {
                Text("OBD not connected", color = DashColors.Muted)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.Background)
                ) { Text("Connect") }
            } else {
                MeterChip("Speed", "${obdData.speedKmh} km/h", obdData.speedKmh / 220f, DashColors.Speed, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("RPM", "${obdData.rpm}", obdData.rpm / 7000f, DashColors.Rpm, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Coolant", "${obdData.coolantTempC} °C", obdData.coolantTempC / 120f, coolantColor(obdData.coolantTempC), false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Intake air", "${obdData.intakeTempC} °C", obdData.intakeTempC / 80f, DashColors.Accent, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Throttle", "${obdData.throttlePct} %", obdData.throttlePct / 100f, DashColors.Accent, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Engine load", "${obdData.engineLoadPct} %", obdData.engineLoadPct / 100f, DashColors.Rpm, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Fuel level", "${obdData.fuelLevelPct} %", obdData.fuelLevelPct / 100f, DashColors.Good, false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MeterChip("Battery", "%.1f V".format(obdData.voltage), ((obdData.voltage - 11.0) / 4.0).toFloat(), if (obdData.voltage in 12.0..15.0) DashColors.Good else DashColors.Warning, false, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Live door status decoded from the MCU door bitfield (65 / 0C / 38, byte 4). */
@Composable
private fun DoorsCard(modifier: Modifier = Modifier) {
    val doors by McuReader.doorState.collectAsState()
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("DOORS", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            val d = doors
            if (d == null) {
                Text("Waiting for MCU data… (needs root)", color = DashColors.Muted)
            } else {
                DoorStatusRow("Front left", d.frontLeft)
                DoorStatusRow("Front right", d.frontRight)
                DoorStatusRow("Rear left", d.rearLeft)
                DoorStatusRow("Rear right", d.rearRight)
                DoorStatusRow("Tailgate", d.tailgate)
                DoorStatusRow("Bonnet", d.bonnet)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (d.anyOpen) "A door is open" else "All closed",
                    color = if (d.anyOpen) DashColors.Warning else DashColors.Good,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DoorStatusRow(label: String, open: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
        Text(
            if (open) "OPEN" else "closed",
            color = if (open) DashColors.Warning else DashColors.Good,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Debug widget: live view of the CANbox/MCU stream (needs root). Each row is a
 * cmdId → bytes; a row highlights when its value changes. Open a door / toggle a
 * light and watch which row flips — that's its cmdId, which we then map to state.
 */
@Composable
private fun CanMonitorCard(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }

    // Two-capture differential: sample many frames while CLOSED, then while OPEN.
    // Keep only rows that are stable within each state (≤2 distinct values) but
    // disjoint between states — that filters out drifting analog sensors and
    // leaves the discrete toggles (door/light/etc.).
    var closed by remember { mutableStateOf<Map<String, Set<String>>?>(null) }
    var opened by remember { mutableStateOf<Map<String, Set<String>>?>(null) }
    var capturing by remember { mutableStateOf<String?>(null) }

    fun capture(which: String) {
        capturing = which
        scope.launch {
            val acc = HashMap<String, MutableSet<String>>()
            val end = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < end) {
                McuReader.entries.value.forEach { e ->
                    acc.getOrPut(e.key) { mutableSetOf() }.add(e.hex)
                }
                delay(70)
            }
            if (which == "A") closed = acc else opened = acc
            capturing = null
        }
    }

    val result = remember(closed, opened) {
        val c = closed
        val o = opened
        if (c == null || o == null) emptyList()
        else (c.keys intersect o.keys).mapNotNull { k ->
            val cs = c.getValue(k)
            val os = o.getValue(k)
            if (cs.size <= 2 && os.size <= 2 && cs.intersect(os).isEmpty()) {
                Triple(k, cs.joinToString(" / "), os.joinToString(" / "))
            } else null
        }.sortedBy { it.first }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Text("CAN MONITOR — find a signal", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(
                "Doors CLOSED → Capture A. Then OPEN the door → Capture B. Only discrete signals that differ are shown.",
                color = DashColors.Muted,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { capture("A") },
                    enabled = capturing == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (closed != null) DashColors.Good else DashColors.Accent,
                        contentColor = DashColors.Background
                    )
                ) { Text(if (capturing == "A") "…" else if (closed != null) "A ✓ closed" else "Capture A") }
                Button(
                    onClick = { capture("B") },
                    enabled = capturing == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (opened != null) DashColors.Good else DashColors.Accent,
                        contentColor = DashColors.Background
                    )
                ) { Text(if (capturing == "B") "…" else if (opened != null) "B ✓ open" else "Capture B") }
                if (closed != null || opened != null) {
                    TextButton(onClick = { closed = null; opened = null }) {
                        Text("Reset", color = DashColors.Muted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                closed == null || opened == null ->
                    Text("Capture A (closed), then B (open) to compare.", color = DashColors.Muted)
                result.isEmpty() ->
                    Text("No clean discrete difference. Do it with the engine OFF so sensors don't drift, and keep the door open during Capture B.", color = DashColors.Muted)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    lazyColumnItems(result, key = { it.first }) { row ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(DashColors.Accent.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(row.first, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold)
                            Text("closed: ${row.second}", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                            Text("open:   ${row.third}", color = DashColors.Accent, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

// Citroën C4 Picasso rough figures for the estimate (until the CANbox gives the
// real distance-to-empty).
private const val TANK_LITERS = 60.0
private const val AVG_L_PER_100KM = 6.5

/**
 * Fuel & range as a radial gauge. Fuel level comes from the **CANbox** (learned
 * via the finder) when available — the C4 Picasso's OBD doesn't report it — and
 * falls back to the OBD fuel PID if that ever works. Range is estimated from the
 * tank size and average consumption until the CANbox gives a real distance.
 */
@Composable
private fun RangeCard(
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The CANbox stream (needs root) is the real fuel source; keep it running
    // while this card is on screen so the learned byte decodes live.
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val canFuel by McuReader.fuelPercent.collectAsState()
    val obdConnected = connection == ObdConnectionState.CONNECTED
    val obdFuel = if (obdConnected) obdData.fuelLevelPct else 0

    // Prefer CANbox fuel; fall back to OBD; null when neither is available yet.
    val fuelPct: Int? = canFuel ?: obdFuel.takeIf { it > 0 }
    val source = if (canFuel != null) "via CANbox" else if (obdFuel > 0) "via OBD" else null

    var showFinder by remember { mutableStateOf(false) }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("FUEL & RANGE", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                source?.let {
                    Text(it, color = if (canFuel != null) DashColors.Good else DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (fuelPct == null) {
                // Nothing yet: explain and offer the finder / OBD connect.
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.LocalGasStation, null, tint = DashColors.Muted, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "This car's OBD doesn't report fuel. Learn it from the CANbox instead — needs root.",
                    color = DashColors.Muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showFinder = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.Background)
                ) {
                    Icon(Icons.Filled.Sensors, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Find fuel signal")
                }
                if (!obdConnected) {
                    TextButton(onClick = onConnect) {
                        Text("Try OBD fuel PID", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                val liters = fuelPct / 100.0 * TANK_LITERS
                val rangeKm = (liters / AVG_L_PER_100KM * 100).toInt()

                Spacer(Modifier.height(6.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnalogGauge(
                        value = fuelPct.toFloat(),
                        maxValue = 100f,
                        valueText = "$rangeKm",
                        label = "KM TO EMPTY",
                        unit = "≈ range",
                        accent = if (fuelPct <= 12) DashColors.Warning else DashColors.Good,
                        // No redline band on fuel (more fill = more fuel); the whole
                        // sweep just turns amber when the tank drops into reserve.
                        redlineFraction = 1f,
                        modifier = Modifier.fillMaxHeight()
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MeterChip("Fuel", "$fuelPct%", fuelPct / 100f, if (fuelPct <= 12) DashColors.Warning else DashColors.Good, false, Modifier.weight(1f))
                    MeterChip("In tank", "%.0f L".format(liters), (liters / TANK_LITERS).toFloat(), DashColors.Speed, false, Modifier.weight(1f))
                    MeterChip("Avg use", "%.1f".format(AVG_L_PER_100KM), 0.5f, DashColors.Accent, false, Modifier.weight(1f))
                }
                // Always reachable, so a learned mapping can be recalibrated or forgotten.
                TextButton(onClick = { showFinder = true }) {
                    Text(
                        if (canFuel == null) "Learn fuel from CANbox" else "Recalibrate fuel",
                        color = DashColors.Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    if (showFinder) {
        FuelFinderDialog(onDismiss = { showFinder = false })
    }
}

/**
 * Learns which CANbox/MCU byte is the fuel level with a **two-point capture**
 * (like the door A/B differential). A single snapshot is unreliable — many bytes
 * happen to read ~60% at one moment, including static ones — so instead the user
 * captures the frames at one fuel level, waits until the gauge has visibly
 * changed, captures again, and we only offer bytes that actually **moved in the
 * same direction** as the fuel and map consistently to the same full tank. That
 * excludes the static byte that got picked before and stayed at 60%.
 */
@Composable
private fun FuelFinderDialog(onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val entries by McuReader.entries.collectAsState()
    var currentPct by remember { mutableIntStateOf(60) }
    var capA by remember { mutableStateOf<Map<String, List<Int>>?>(null) }
    var pctA by remember { mutableIntStateOf(0) }
    var capB by remember { mutableStateOf<Map<String, List<Int>>?>(null) }
    var pctB by remember { mutableIntStateOf(0) }

    fun snapshot(): Map<String, List<Int>> = entries.associate { it.key to it.bytes }

    // Bytes that changed in the same direction as the fuel and map to a
    // consistent full-tank raw across both captures. err = disagreement between
    // the two implied full-tank values (lower = better fit).
    data class Cand(val key: String, val index: Int, val rawA: Int, val rawB: Int, val fullRaw: Int, val err: Int)
    val candidates = remember(capA, capB, pctA, pctB) {
        val a = capA; val b = capB
        if (a == null || b == null || pctA == pctB || pctA == 0 || pctB == 0) {
            emptyList()
        } else {
            val fuelDir = if (pctB > pctA) 1 else -1
            val out = ArrayList<Cand>()
            for ((key, av) in a) {
                val bv = b[key] ?: continue
                val n = minOf(av.size, bv.size)
                for (i in 0 until n) {
                    val ra = av[i]; val rb = bv[i]
                    if (ra !in 1..255 || rb !in 1..255 || ra == rb) continue
                    val byteDir = if (rb > ra) 1 else -1
                    if (byteDir != fuelDir) continue                       // must track fuel
                    val fullA = ra * 100f / pctA
                    val fullB = rb * 100f / pctB
                    val fullRaw = ((fullA + fullB) / 2f).roundToInt().coerceIn(1, 255)
                    if (fullRaw < maxOf(ra, rb)) continue                  // full tank ≥ current
                    out.add(Cand(key, i, ra, rb, fullRaw, kotlin.math.abs(fullA - fullB).roundToInt()))
                }
            }
            out.sortedWith(compareBy({ it.err }, { -kotlin.math.abs(it.rawB - it.rawA) })).take(12)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text("Find fuel signal", color = DashColors.TextPrimary) },
        text = {
            Column {
                Text(
                    "Two-step: set your dash gauge %, tap Capture A. Later — once the gauge has changed a few % — set the new value and tap Capture B. Only bytes that actually moved with the fuel are offered.",
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dash reads", color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    FilledIconButton(
                        onClick = { currentPct = (currentPct - 5).coerceAtLeast(5) },
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text("−") }
                    Text("$currentPct%", color = DashColors.TextPrimary, fontWeight = FontWeight.Bold)
                    FilledIconButton(
                        onClick = { currentPct = (currentPct + 5).coerceAtMost(100) },
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text("+") }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { capA = snapshot(); pctA = currentPct },
                        colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Accent)
                    ) { Text(if (capA == null) "Capture A" else "A ✓ $pctA%") }
                    TextButton(
                        onClick = { capB = snapshot(); pctB = currentPct },
                        enabled = capA != null,
                        colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Accent)
                    ) { Text(if (capB == null) "Capture B" else "B ✓ $pctB%") }
                    if (capA != null || capB != null) {
                        TextButton(
                            onClick = { capA = null; capB = null; pctA = 0; pctB = 0 },
                            colors = ButtonDefaults.textButtonColors(contentColor = DashColors.Muted)
                        ) { Text("Reset") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                when {
                    entries.isEmpty() ->
                        Text("Waiting for CANbox data… (needs root)", color = DashColors.Muted)
                    capA == null ->
                        Text("Set your current fuel %, then tap Capture A.", color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                    capB == null ->
                        Text("Captured at $pctA%. Drive until the gauge drops a few %, set the new value, then Capture B.", color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                    pctA == pctB ->
                        Text("A and B are the same %. Capture B at a different fuel level.", color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                    candidates.isEmpty() ->
                        Text("No byte tracked the change. Recapture B after a bigger drop.", color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text("Bytes that moved with the fuel", color = DashColors.Accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(210.dp)) {
                            lazyColumnItems(candidates, key = { "${it.key}#${it.index}" }) { c ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DashColors.CardHi)
                                        .clickable {
                                            McuReader.saveFuelMapping(c.key, c.index, c.fullRaw)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("frame ${c.key}  ·  byte ${c.index}", color = DashColors.TextPrimary, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                        Text("${c.rawA} → ${c.rawB}  ·  full≈${c.fullRaw}", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Icon(Icons.Filled.LocalGasStation, null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (McuReader.fuelConfigured) {
                TextButton(onClick = { McuReader.clearFuelMapping(); onDismiss() }) {
                    Text("Forget current", color = DashColors.Warning)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = DashColors.Muted) }
        }
    )
}

@Composable
private fun AppDrawer(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 6.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "All apps",
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close app drawer",
                        tint = DashColors.TextSecondary
                    )
                }
            }

            if (apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No apps found", color = DashColors.Muted)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 92.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        AppShortcutTile(app = app, packageName = app.packageName, onClick = { onLaunch(app) })
                    }
                }
            }
        }
    }
}

// --- Shared building blocks --------------------------------------------------

/**
 * Rounded elevated card. Solid themes use a flat surface matching the Android
 * Auto content cards; glass themes use a translucent gradient panel that lets
 * the aurora background show through.
 */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    if (DashColors.Glass) {
        Box(modifier = modifier.then(glassPanel(shape))) { content() }
    } else {
        Surface(
            modifier = modifier,
            color = DashColors.Card,
            shape = shape,
            content = content
        )
    }
}

/**
 * Glass surface: translucent white->accent gradient fill, hairline border and a
 * specular highlight along the top edge. The head unit is Android 10, so there
 * is no RenderEffect backdrop blur; the layered translucency carries the look.
 */
@Composable
private fun glassPanel(shape: RoundedCornerShape): Modifier {
    val line = DashColors.Line
    val accent = DashColors.Accent
    return Modifier
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.10f),
                    Color.White.copy(alpha = 0.035f),
                    accent.copy(alpha = 0.06f)
                )
            )
        )
        .border(1.dp, line, shape)
        .drawWithContent {
            drawContent()
            val inset = size.width * 0.18f
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                    startX = inset, endX = size.width - inset
                ),
                start = Offset(inset, 1f),
                end = Offset(size.width - inset, 1f),
                strokeWidth = 1.5f
            )
        }
}

/**
 * Page background: the theme's gradient, plus (on glass themes) a cool wash
 * from the top and a violet wash from the bottom-right corner.
 */
@Composable
private fun dashBackground(): Modifier {
    val stops = DashColors.BackgroundStops
    val glass = DashColors.Glass
    val glow = DashColors.Glow
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    return Modifier.drawBehind {
        drawRect(
            brush = Brush.linearGradient(
                colors = stops,
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
        if (glass) {
            val topC = Offset(size.width * 0.5f, -size.height * 0.15f)
            val topR = size.width * 0.45f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.22f * glow), Color.Transparent),
                    center = topC, radius = topR
                ),
                radius = topR, center = topC
            )
            val cornerC = Offset(size.width * 1.05f, size.height * 1.05f)
            val cornerR = size.width * 0.40f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent2.copy(alpha = 0.24f * glow), Color.Transparent),
                    center = cornerC, radius = cornerR
                ),
                radius = cornerR, center = cornerC
            )
        }
    }
}

/**
 * Gradient that follows the gauge arc (135deg -> 405deg). Compose sweep gradients
 * start at 3 o'clock, so the stops are placed in that frame: the arc start
 * (135deg = 0.375) is [start], the arc end (45deg = 0.125, wrapped) is [end].
 */
private fun gaugeSweepBrush(center: Offset, start: Color, end: Color): Brush {
    val mid = lerp(start, end, 0.35f)
    val atZero = lerp(mid, end, 0.65f)
    return Brush.sweepGradient(
        0f to atZero,
        0.125f to end,
        0.375f to start,
        0.7875f to mid,
        1f to atZero,
        center = center
    )
}

/** Average colour of a bitmap (used for the album-art colour bleed). */
private fun Bitmap.averageColor(): Color = runCatching {
    Color(Bitmap.createScaledBitmap(this, 1, 1, true).getPixel(0, 0))
}.getOrDefault(Color.Gray)

/** Renders an installed app's launcher [Drawable] as a Compose image. */
@Composable
private fun AppIcon(icon: Drawable, size: androidx.compose.ui.unit.Dp) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(icon, px) {
        icon.toBitmap(width = px.coerceAtLeast(1), height = px.coerceAtLeast(1)).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}

// --- Helpers -----------------------------------------------------------------

private fun currentClock(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Lets the user pick which paired Bluetooth device is the OBD adapter. */
@Composable
private fun DevicePickerDialog(
    devices: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text("Select OBD adapter", color = DashColors.TextPrimary) },
        text = {
            Column {
                if (devices.isEmpty()) {
                    Text(
                        "No paired Bluetooth devices. Pair your OBD adapter in Bluetooth settings first.",
                        color = DashColors.TextSecondary
                    )
                } else {
                    devices.forEach { (name, mac) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(mac) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(name, color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
                            Text(mac, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Bluetooth settings", color = DashColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = DashColors.Muted)
            }
        }
    )
}

// Only BLUETOOTH_CONNECT is needed (and declared) to talk to a bonded adapter.
private fun requiredBluetoothPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }
