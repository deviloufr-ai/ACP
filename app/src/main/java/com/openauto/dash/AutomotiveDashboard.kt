@file:OptIn(ExperimentalFoundationApi::class)

package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * The launcher's semantic palette. Every screen reads its colours from here, so
 * flipping the active [DashPalette] re-themes the whole UI at once.
 *
 * The head unit already switches its system day/night mode from the car's light
 * sensor (headlights on -> night). We mirror that: [DarkPalette] (near-black,
 * glare-free) at night, [LightPalette] (bright, high-contrast) by day. The swap
 * is driven by [DashColors.SyncWithSystem], called once at the dashboard root.
 */
private data class DashPalette(
    val Background: Color,
    val Bar: Color,
    val Card: Color,
    val CardHi: Color,
    val Accent: Color,
    val Speed: Color,
    val Rpm: Color,
    val Warning: Color,
    val Good: Color,
    val Muted: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
)

/**
 * Android Auto ("Coolwalk") inspired night palette: near-black backdrop,
 * elevated dark cards, Google-blue accent and the four Assistant brand colours.
 */
private val DarkPalette = DashPalette(
    Background = Color(0xFF0B0C0F),
    Bar = Color(0xFF141518),
    Card = Color(0xFF1E2024),
    CardHi = Color(0xFF2A2D33),
    Accent = Color(0xFF8AB4F8),
    Speed = Color(0xFF8AB4F8),
    Rpm = Color(0xFFF6AD7B),
    Warning = Color(0xFFF28B82),
    Good = Color(0xFF81C995),
    Muted = Color(0xFF9AA0A6),
    TextPrimary = Color(0xFFE8EAED),
    TextSecondary = Color(0xFF9AA0A6),
)

/**
 * Daytime light palette: soft off-white backdrop, white cards and the deeper,
 * fully saturated Google hues so text and accents stay legible in sunlight.
 */
private val LightPalette = DashPalette(
    Background = Color(0xFFF1F3F4),
    Bar = Color(0xFFFFFFFF),
    Card = Color(0xFFFFFFFF),
    CardHi = Color(0xFFE3E6EA),
    Accent = Color(0xFF1A73E8),
    Speed = Color(0xFF1A73E8),
    Rpm = Color(0xFFE8710A),
    Warning = Color(0xFFD93025),
    Good = Color(0xFF188038),
    Muted = Color(0xFF5F6368),
    TextPrimary = Color(0xFF202124),
    TextSecondary = Color(0xFF5F6368),
)

/**
 * Live palette proxy. Composables read [DashColors].Background etc. exactly as
 * before; the backing [current] palette is a snapshot state, so a day/night swap
 * recomposes everything that reads a colour.
 */
private object DashColors {
    private var current by mutableStateOf(DarkPalette)

    /**
     * Follow the OS day/night signal (which the head unit derives from the car's
     * light sensor). Call once from the root composable; it re-runs on config
     * changes because [isSystemInDarkTheme] subscribes to the UI mode.
     */
    @Composable
    fun SyncWithSystem() {
        val target = if (isSystemInDarkTheme()) DarkPalette else LightPalette
        if (current !== target) current = target
    }

    val Background: Color get() = current.Background
    val Bar: Color get() = current.Bar
    val Card: Color get() = current.Card
    val CardHi: Color get() = current.CardHi
    val Accent: Color get() = current.Accent
    val Speed: Color get() = current.Speed
    val Rpm: Color get() = current.Rpm
    val Warning: Color get() = current.Warning
    val Good: Color get() = current.Good
    val Muted: Color get() = current.Muted
    val TextPrimary: Color get() = current.TextPrimary
    val TextSecondary: Color get() = current.TextSecondary
}

private const val SPEED_WARNING_KMH = 110

/**
 * Simple car launcher: three swipeable "virtual desktop" dashboards. Each page
 * is a grid the user fills with app shortcuts and widgets (our built-in Maps /
 * media / OBD cards, or any real Android app-widget) via the "+" tile. A single
 * Apps button opens the full app drawer. Nothing launches automatically.
 */
@Composable
fun AutomotiveDashboard(inSplitMode: Boolean = false) {
    // Re-theme light/dark from the head unit's day/night mode (car light sensor).
    DashColors.SyncWithSystem()

    val context = LocalContext.current
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

    fun addItem(page: Int, item: DashboardItem) = mutatePage(page) { it + item }

    fun removeAt(page: Int, index: Int) {
        val item = pages.getOrNull(page)?.getOrNull(index) ?: return
        if (item is DashboardItem.SystemWidget) WidgetHostHolder.delete(context, item.appWidgetId)
        mutatePage(page) { list -> list.filterIndexed { i, _ -> i != index } }
    }

    // Reorder a tile within its page (edit-mode ◀ ▶ buttons).
    fun moveItem(page: Int, from: Int, to: Int) {
        mutatePage(page) { list ->
            if (from !in list.indices || to !in list.indices) list
            else list.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    // System app-widget picker; adds the bound widget to the page that requested it.
    val addSystemWidget = rememberSystemWidgetAdder { id ->
        if (addTargetPage in 0 until DashboardStore.PAGE_COUNT) {
            addItem(addTargetPage, DashboardItem.SystemWidget(id))
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
            .background(DashColors.Background)
    ) {
        TopBar(
            currentPage = pagerState.currentPage,
            clock = clock,
            versionName = updateManager.currentVersionName,
            obdConnection = obdConnection,
            editing = editing,
            onApps = { showAllApps = true },
            onMaps = { SplitLauncher.launchSplit(context, "com.google.android.apps.maps") },
            onSplit = {
                if (SplitLauncher.isSystemSplitAvailable()) showSplitPicker = true
                else showSplitEnable = true
            },
            onToggleEdit = { editing = !editing },
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
                    onResize = { index, weight ->
                        mutatePage(page) { list ->
                            list.mapIndexed { i, it -> if (i == index) it.withWeight(weight) else it }
                        }
                    },
                    onMove = { from, to -> moveItem(page, from, to) },
                    onToggleHalf = { index ->
                        mutatePage(page) { list ->
                            list.mapIndexed { i, it -> if (i == index) it.withHalf(!it.isHalf()) else it }
                        }
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
    editing: Boolean,
    onApps: () -> Unit,
    onMaps: () -> Unit,
    onSplit: () -> Unit,
    onToggleEdit: () -> Unit,
    onSystem: () -> Unit
) {
    Surface(color = DashColors.Bar, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Apps")
            }

            Text(
                text = "Dashboard ${currentPage + 1}",
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.weight(1f))

            val dotColor = when (obdConnection) {
                ObdConnectionState.CONNECTED -> DashColors.Good
                ObdConnectionState.CONNECTING -> DashColors.Speed
                ObdConnectionState.ERROR -> DashColors.Warning
                ObdConnectionState.DISCONNECTED -> DashColors.Muted
            }
            Icon(
                imageVector = if (obdConnection == ObdConnectionState.CONNECTED) {
                    Icons.Filled.BluetoothConnected
                } else {
                    Icons.Filled.Bluetooth
                },
                contentDescription = null,
                tint = dotColor,
                modifier = Modifier.size(16.dp)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(clock, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("v$versionName", color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
            }

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
                    .padding(horizontal = 5.dp)
                    .size(if (index == current) 11.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (index == current) DashColors.Accent else DashColors.CardHi)
                    .clickable { onSelect(index) }
            )
        }
    }
}

// --- A single dashboard page -------------------------------------------------

/** Natural height of a full widget tile stacked in the scrollable split-screen column. */
private val SPLIT_WIDGET_HEIGHT = 300.dp

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
    onResize: (Int, Float) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleHalf: (Int) -> Unit,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Long-press drag-to-reorder, like rearranging icons on an Android home
    // screen. Every tile reports its on-screen bounds; while a tile is held and
    // dragged we find which tile the finger is over and reorder the list live, so
    // the other tiles flow out of the way. App shortcuts and split-pair tiles are
    // ordinary tiles here, so they reorder freely amongst each other and the
    // widget cards. The edit-mode ◀ ▶ buttons remain as an explicit alternative.
    val tileBounds = remember { mutableStateMapOf<Int, Rect>() }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) } // finger position, root coords
    var grabOffset by remember { mutableStateOf(Offset.Zero) }  // finger offset within the grabbed tile

    // Drop bounds for slots that no longer exist (e.g. after a tile is removed) so
    // a stale rectangle can't be picked as a drop target on the next drag.
    LaunchedEffect(pageItems.size) {
        tileBounds.keys.filter { it >= pageItems.size }.forEach { tileBounds.remove(it) }
    }

    // Side-by-side columns that fill the screen (tuned for 1280x720). Widgets take
    // weighted shares of the width; app shortcuts stay compact. A widget marked
    // "half" (edit mode) takes half the height so two stack in one column instead
    // of each eating a full column. Swiping moves between the 3 dashboards.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rowWidthPx = with(density) { maxWidth.toPx() }
        val totalWeight = pageItems.filterNot { it.isCompactTile() }
            .sumOf { it.tileWeight().toDouble() }.toFloat().coerceAtLeast(0.01f)
        val weightPerPx = totalWeight / rowWidthPx.coerceAtLeast(1f)

        // Group the flat item list into columns: each full widget / compact tile
        // is its own column; consecutive half widgets pack two-per-column (stacked).
        val columns = ArrayList<MutableList<Int>>()
        run {
            var halfBuf: MutableList<Int>? = null
            pageItems.forEachIndexed { i, it ->
                if (!it.isCompactTile() && it.isHalf()) {
                    val buf = halfBuf?.takeIf { b -> b.size < 2 }
                        ?: ArrayList<Int>().also { columns.add(it); halfBuf = it }
                    buf.add(i)
                } else {
                    halfBuf = null
                    columns.add(arrayListOf(i))
                }
            }
        }

        // Reorder drag is offered in the normal (non-split) layout only; the split
        // pane is a scrolling column where vertical drags belong to the scroller.
        val reorderable = !inSplitMode && pageItems.size > 1

        val renderTile: @Composable (Int, Modifier) -> Unit = { index, mod ->
            val item = pageItems[index]
            val dragging = dragIndex == index
            val tileModifier = mod
                .onGloballyPositioned { coords ->
                    tileBounds[index] = Rect(coords.positionInRoot(), coords.size.toSize())
                }
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer {
                    if (dragging) {
                        val settled = tileBounds[index]
                        if (settled != null) {
                            translationX = dragPointer.x - grabOffset.x - settled.left
                            translationY = dragPointer.y - grabOffset.y - settled.top
                        }
                        scaleX = 1.08f
                        scaleY = 1.08f
                        alpha = 0.95f
                        shadowElevation = 24f
                    }
                }
                .let { base ->
                    if (!reorderable) base
                    else base.pointerInput(index, pageItems.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startLocal ->
                                val b = tileBounds[index] ?: return@detectDragGesturesAfterLongPress
                                grabOffset = startLocal
                                dragPointer = b.topLeft + startLocal
                                dragIndex = index
                                onModelTouch(true) // lock the pager while dragging
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                dragPointer += delta
                                val from = dragIndex ?: return@detectDragGesturesAfterLongPress
                                val target = tileBounds.entries
                                    .firstOrNull { (i, r) -> i != from && r.contains(dragPointer) }
                                    ?.key
                                if (target != null && target != from) {
                                    onMove(from, target)
                                    dragIndex = target
                                }
                            },
                            onDragEnd = { dragIndex = null; onModelTouch(false) },
                            onDragCancel = { dragIndex = null; onModelTouch(false) }
                        )
                    }
                }
            EditableTile(
                modifier = tileModifier,
                editing = editing && !inSplitMode,
                resizable = !inSplitMode && !item.isCompactTile(),
                canMoveLeft = index > 0,
                canMoveRight = index < pageItems.lastIndex,
                onMoveLeft = { onMove(index, index - 1) },
                onMoveRight = { onMove(index, index + 1) },
                halfToggle = !item.isCompactTile(),
                isHalf = item.isHalf(),
                onToggleHalf = { onToggleHalf(index) },
                onRemove = { onRemove(index) },
                onResizeActive = onModelTouch,
                onResizeBy = { deltaPx ->
                    val next = (item.tileWeight() + deltaPx * weightPerPx)
                        .coerceIn(DashboardStore.MIN_TILE_WEIGHT, DashboardStore.MAX_TILE_WEIGHT)
                    onResize(index, next)
                }
            ) {
                TileContent(
                    item = item,
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
        }

        if (inSplitMode) {
            // Sharing the screen: the pane is narrow and tall. Stack tiles
            // vertically at their natural height (no shrinking to fit) and let
            // the column scroll when they overflow the pane.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pageItems.indices.forEach { index ->
                    val item = pageItems[index]
                    val h = if (item.isCompactTile()) 96.dp else SPLIT_WIDGET_HEIGHT
                    renderTile(index, Modifier.fillMaxWidth().height(h))
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                columns.forEach { col ->
                    val first = pageItems[col.first()]
                    when {
                        first.isCompactTile() -> {
                            val w = if (first is DashboardItem.SplitPair) 120.dp else 104.dp
                            renderTile(col.first(), Modifier.width(w).fillMaxHeight())
                        }
                        col.size > 1 || first.isHalf() -> {
                            val colWeight = col.maxOf { pageItems[it].tileWeight() }
                            Column(
                                modifier = Modifier.weight(colWeight).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                col.forEach { index ->
                                    renderTile(index, Modifier.weight(1f).fillMaxWidth())
                                }
                            }
                        }
                        else -> renderTile(col.first(), Modifier.weight(first.tileWeight()).fillMaxHeight())
                    }
                }

                Box(
                    modifier = Modifier.width(96.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    AddTile(onClick = onAdd)
                }
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
                onClick = { onLaunchSplitPair(item.primaryPackage, item.secondaryPackage) }
            )
        }

        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.NAVMAP -> Box(
                modifier = Modifier.fillMaxSize().background(DashColors.Card)
            ) {
                MapLibrePanel(modifier = Modifier.fillMaxSize())
            }
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

/**
 * Wraps a tile, overlaying a remove (×) badge while [editing]. For [resizable]
 * tiles it also shows a drag handle on the right edge that widens/narrows the
 * tile; horizontal drags there are reported via [onResizeBy] (pixels) and
 * [onResizeActive] toggles the pager swipe lock so the drag isn't stolen.
 */
@Composable
private fun EditableTile(
    modifier: Modifier = Modifier,
    editing: Boolean,
    resizable: Boolean = false,
    canMoveLeft: Boolean = false,
    canMoveRight: Boolean = false,
    onMoveLeft: () -> Unit = {},
    onMoveRight: () -> Unit = {},
    halfToggle: Boolean = false,
    isHalf: Boolean = false,
    onToggleHalf: () -> Unit = {},
    onRemove: () -> Unit,
    onResizeActive: (Boolean) -> Unit = {},
    onResizeBy: (Float) -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
        if (editing) {
            FilledIconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(30.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = DashColors.Warning,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
            if (halfToggle) {
                FilledIconButton(
                    onClick = onToggleHalf,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(30.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isHalf) DashColors.Accent else DashColors.CardHi,
                        contentColor = if (isHalf) DashColors.Background else DashColors.TextPrimary
                    )
                ) {
                    Icon(Icons.Filled.Splitscreen, contentDescription = "Toggle half height", modifier = Modifier.size(17.dp))
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (canMoveLeft) {
                    FilledIconButton(
                        onClick = onMoveLeft,
                        modifier = Modifier.size(30.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary
                        )
                    ) { Text("◀") }
                }
                if (canMoveRight) {
                    FilledIconButton(
                        onClick = onMoveRight,
                        modifier = Modifier.size(30.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary
                        )
                    ) { Text("▶") }
                }
            }
            if (resizable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp)
                        .width(26.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(DashColors.Accent.copy(alpha = 0.85f))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { onResizeActive(true) },
                                onDragEnd = { onResizeActive(false) },
                                onDragCancel = { onResizeActive(false) },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onResizeBy(dragAmount.x)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SwapHoriz,
                        contentDescription = "Resize width",
                        tint = DashColors.Background,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppShortcutTile(app: AppEntry?, packageName: String, onClick: () -> Unit) {
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
                .background(DashColors.CardHi),
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
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
                        .size(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DashColors.CardHi),
                    contentAlignment = Alignment.Center
                ) {
                    val art = mediaState.artwork
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
                            tint = DashColors.TextSecondary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NOW PLAYING",
                        color = DashColors.Accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
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
                        text = mediaState.artist.ifBlank { if (hasAccess) "—" else "Tap to enable" },
                        color = DashColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (hasAccess) {
                if (mediaState.durationMs > 0L) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = DashColors.Accent,
                        trackColor = DashColors.CardHi
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
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
                    IconButton(onClick = { controller.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = DashColors.TextPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    FilledIconButton(
                        onClick = { controller.playPause() },
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = DashColors.Accent,
                            contentColor = DashColors.Background
                        )
                    ) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { controller.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = DashColors.TextPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { CarMediaController.openNotificationAccessSettings(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DashColors.Accent,
                        contentColor = DashColors.Background
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Media Access")
                }
            }
        }
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
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
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
                    style = MaterialTheme.typography.labelMedium
                )
                if (connected) {
                    TextButton(onClick = onPickDevice, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.BluetoothConnected, null, tint = DashColors.Good, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Live", color = DashColors.Good, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = connection != ObdConnectionState.CONNECTING,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DashColors.Accent,
                            contentColor = DashColors.Background
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Bluetooth, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (connection == ObdConnectionState.CONNECTING) "…" else "Connect")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // The two racing gauges fill most of the card, side by side.
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    modifier = Modifier.weight(1.15f).fillMaxHeight()
                )
                AnalogGauge(
                    value = if (connected) obdData.rpm.toFloat() else 0f,
                    maxValue = 7000f,
                    valueText = if (connected) obdData.rpm.toString() else "--",
                    label = "RPM",
                    unit = "rpm",
                    accent = DashColors.Rpm,
                    redlineAccent = DashColors.Warning,
                    redlineFraction = 0.82f,
                    dimmed = !connected,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }

            Spacer(Modifier.height(8.dp))

            // Secondary readouts as compact meter chips.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MeterChip(
                    label = "Coolant",
                    valueText = if (connected) "${obdData.coolantTempC}°" else "--",
                    fraction = (obdData.coolantTempC / 120f),
                    color = coolantColor(obdData.coolantTempC),
                    dimmed = !connected,
                    modifier = Modifier.weight(1f)
                )
                MeterChip(
                    label = "Load",
                    valueText = if (connected) "${obdData.engineLoadPct}%" else "--",
                    fraction = obdData.engineLoadPct / 100f,
                    color = DashColors.Accent,
                    dimmed = !connected,
                    modifier = Modifier.weight(1f)
                )
                MeterChip(
                    label = "Battery",
                    valueText = if (connected) "%.1fV".format(obdData.voltage) else "--",
                    fraction = ((obdData.voltage - 11.0) / 4.0).toFloat(),
                    color = if (obdData.voltage in 12.0..15.0) DashColors.Good else DashColors.Warning,
                    dimmed = !connected,
                    modifier = Modifier.weight(1f)
                )
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
    majorTicks: Int = 9
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

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val gaugePx = min(maxWidth.value, maxHeight.value)
        val valueSize = (gaugePx * 0.20f).coerceIn(16f, 46f).sp
        val unitSize = (gaugePx * 0.075f).coerceIn(8f, 14f).sp
        val labelSize = (gaugePx * 0.085f).coerceIn(9f, 15f).sp

        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.085f
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // Base track.
            drawArc(
                color = DashColors.CardHi,
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
            // Active sweep (glow underlay + solid).
            if (frac > 0f) {
                drawArc(
                    color = sweepColor.copy(alpha = 0.25f),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 1.9f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = sweepColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            // Tick marks.
            val tickOuter = radius - stroke * 0.6f
            val tickInner = radius - stroke * 1.5f
            for (i in 0 until majorTicks) {
                val a = Math.toRadians((startAngle + sweepTotal * i / (majorTicks - 1)).toDouble())
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                drawLine(
                    color = DashColors.TextSecondary.copy(alpha = 0.6f),
                    start = Offset(center.x + ca * tickInner, center.y + sa * tickInner),
                    end = Offset(center.x + ca * tickOuter, center.y + sa * tickOuter),
                    strokeWidth = stroke * 0.16f,
                    cap = StrokeCap.Round
                )
            }
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

        // Digital readout in the middle.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = gaugePx.times(0.10f).dp)
        ) {
            Text(
                text = valueText,
                color = if (dimmed) DashColors.Muted else DashColors.TextPrimary,
                fontSize = valueSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(text = unit, color = DashColors.Muted, fontSize = unitSize)
            Spacer(Modifier.height(2.dp))
            Text(text = label, color = accent, fontSize = labelSize, fontWeight = FontWeight.SemiBold)
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
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DashColors.CardHi)
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
                .background(DashColors.Background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (dimmed) 0f else fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(color)
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

/** Rounded elevated card, matching the Android Auto content surfaces. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = DashColors.Card,
        shape = RoundedCornerShape(24.dp),
        content = content
    )
}

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
