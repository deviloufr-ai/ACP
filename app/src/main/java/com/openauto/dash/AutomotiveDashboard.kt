package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Auto ("Coolwalk") inspired palette: near-black backdrop, elevated
 * dark cards, Google-blue accent and the four Assistant brand colors.
 */
private object DashColors {
    val Background = Color(0xFF0B0C0F)
    val Taskbar = Color(0xFF141518)
    val Card = Color(0xFF1E2024)
    val CardHi = Color(0xFF2A2D33)
    val Accent = Color(0xFF8AB4F8)
    val Speed = Color(0xFF8AB4F8)
    val Rpm = Color(0xFFF6AD7B)
    val Warning = Color(0xFFF28B82)
    val Good = Color(0xFF81C995)
    val Muted = Color(0xFF9AA0A6)
    val TextPrimary = Color(0xFFE8EAED)
    val TextSecondary = Color(0xFF9AA0A6)

    // Google Assistant brand colors.
    val GBlue = Color(0xFF4285F4)
    val GRed = Color(0xFFEA4335)
    val GYellow = Color(0xFFFBBC05)
    val GGreen = Color(0xFF34A853)
}

private const val SPEED_WARNING_KMH = 110
private const val MAX_FAVORITES = 5

/**
 * Android Auto style car launcher.
 *
 * A dark [Taskbar] runs down the left edge (app launcher, favorites, Google
 * Assistant, clock). The content area is map-dominant: [MapsPanel] auto-opens
 * Google Maps, and a card column shows the now-playing media card and OBD
 * telemetry — or the full app drawer when "All apps" is open.
 */
@Composable
fun AutomotiveDashboard() {
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
    val favorites = remember(apps) { AppLauncher.pickFavorites(apps, MAX_FAVORITES) }

    var showAllApps by remember { mutableStateOf(false) }
    var hasMediaAccess by remember { mutableStateOf(CarMediaController.hasNotificationAccess(context)) }

    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClock()
            delay(1000)
        }
    }

    // Start media observation, and re-check notification access on every resume
    // so granting it in system settings takes effect without an app restart.
    DisposableEffect(lifecycleOwner) {
        ObdBluetoothManager.setContext(context)
        mediaController.start()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMediaAccess = CarMediaController.hasNotificationAccess(context)
                if (hasMediaAccess) mediaController.start()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mediaController.stop()
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            scope.launch { connectOrOpenSettings(context) }
        }
    }

    val onConnectObd: () -> Unit = {
        val missing = requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            scope.launch { connectOrOpenSettings(context) }
        }
    }

    val onLaunchApp: (AppEntry) -> Unit = { app ->
        if (AppLauncher.launch(context, app.packageName)) {
            showAllApps = false
        }
    }

    // Enters "cockpit" mode: Maps + last-used media in a real system split, with
    // this launcher's menu/info floating on top as an overlay widget.
    fun startCockpit() {
        LauncherOverlayService.start(context)
        SplitScreenLauncher.launchCockpit(context)
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) startCockpit()
    }

    val onCockpit: () -> Unit = {
        if (Settings.canDrawOverlays(context)) {
            startCockpit()
        } else {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(DashColors.Background)
    ) {
        Taskbar(
            favorites = favorites,
            showAllApps = showAllApps,
            clock = clock,
            versionName = updateManager.currentVersionName,
            obdConnection = obdConnection,
            onLaunch = onLaunchApp,
            onToggleAllApps = { showAllApps = !showAllApps },
            onAssistant = { launchAssistant(context) },
            onCockpit = onCockpit
        )

        Column(modifier = Modifier.fillMaxSize()) {
            UpdateBanner(
                status = updateStatus,
                onUpdate = onUpdate,
                onDismiss = { updateManager.dismiss() }
            )

            val cards: @Composable (Modifier) -> Unit = { mod ->
                if (showAllApps) {
                    AppDrawer(
                        apps = apps,
                        onLaunch = onLaunchApp,
                        onClose = { showAllApps = false },
                        modifier = mod
                    )
                } else {
                    CardStack(
                        mediaState = mediaState,
                        controller = mediaController,
                        hasMediaAccess = hasMediaAccess,
                        context = context,
                        obdData = obdData,
                        connection = obdConnection,
                        onConnect = onConnectObd,
                        modifier = mod
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                if (maxWidth >= maxHeight) {
                    // Landscape: map dominant, cards on the right.
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MapsCard(
                            modifier = Modifier
                                .weight(1.6f)
                                .fillMaxHeight()
                        )
                        cards(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    // Portrait: map on top, cards below.
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MapsCard(
                            modifier = Modifier
                                .weight(1.3f)
                                .fillMaxWidth()
                        )
                        cards(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

// --- Left taskbar (Android Auto style) ---------------------------------------

@Composable
private fun Taskbar(
    favorites: List<AppEntry>,
    showAllApps: Boolean,
    clock: String,
    versionName: String,
    obdConnection: ObdConnectionState,
    onLaunch: (AppEntry) -> Unit,
    onToggleAllApps: () -> Unit,
    onAssistant: () -> Unit,
    onCockpit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(96.dp),
        color = DashColors.Taskbar
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App launcher (all apps).
            TaskbarButton(
                selected = showAllApps,
                onClick = onToggleAllApps
            ) {
                Icon(
                    imageVector = Icons.Filled.Apps,
                    contentDescription = "All apps",
                    tint = if (showAllApps) DashColors.Background else DashColors.TextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Cockpit: launch Maps + last-used media in a system split screen.
            TaskbarButton(
                selected = false,
                onClick = onCockpit
            ) {
                Icon(
                    imageVector = Icons.Filled.Splitscreen,
                    contentDescription = "Split-screen cockpit",
                    tint = DashColors.TextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            favorites.forEach { app ->
                TaskbarAppButton(app = app, onClick = { onLaunch(app) })
                Spacer(Modifier.height(14.dp))
            }

            Spacer(Modifier.weight(1f))

            // Google Assistant.
            AssistantButton(onClick = onAssistant)

            Spacer(Modifier.height(16.dp))

            // OBD status dot + clock cluster.
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
            Spacer(Modifier.height(8.dp))
            Text(
                text = clock,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "v$versionName",
                color = DashColors.Muted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun TaskbarAppButton(app: AppEntry, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(DashColors.CardHi)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(icon = app.icon, size = 40.dp)
    }
}

@Composable
private fun TaskbarButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(if (selected) DashColors.Accent else DashColors.CardHi)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun AssistantButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(DashColors.GBlue, DashColors.GRed, DashColors.GYellow, DashColors.GGreen)
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Google Assistant",
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
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

/** Rounded map surface — the dominant, always-on navigation panel. */
@Composable
private fun MapsCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = DashColors.Card
    ) {
        MapsPanel(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CardStack(
    mediaState: MediaState,
    controller: CarMediaController,
    hasMediaAccess: Boolean,
    context: Context,
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MediaCard(
            mediaState = mediaState,
            controller = controller,
            hasAccess = hasMediaAccess,
            context = context,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        ObdCard(
            obdData = obdData,
            connection = connection,
            onConnect = onConnect,
            modifier = Modifier.fillMaxWidth()
        )
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
    // Advance a local position estimate while something is playing.
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
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = DashColors.TextSecondary,
                        modifier = Modifier.size(38.dp)
                    )
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
                        trackColor = DashColors.CardHi,
                        drawStopIndicator = {}
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
                    IconButton(
                        onClick = { controller.previous() },
                        modifier = Modifier.size(56.dp)
                    ) {
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
                    IconButton(
                        onClick = { controller.next() },
                        modifier = Modifier.size(56.dp)
                    ) {
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
    modifier: Modifier = Modifier
) {
    val connected = connection == ObdConnectionState.CONNECTED
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HudStat(
                label = "Speed",
                value = if (connected) obdData.speedKmh.toString() else "--",
                unit = "km/h",
                valueColor = when {
                    !connected -> DashColors.Muted
                    obdData.speedKmh > SPEED_WARNING_KMH -> DashColors.Warning
                    else -> DashColors.Speed
                }
            )
            HudStat(
                label = "RPM",
                value = if (connected) obdData.rpm.toString() else "--",
                unit = "rpm",
                valueColor = if (connected) DashColors.Rpm else DashColors.Muted
            )
            HudStat(
                label = "Coolant",
                value = if (connected) obdData.coolantTempC.toString() else "--",
                unit = "°C",
                valueColor = if (connected) DashColors.Good else DashColors.Muted
            )
            Button(
                onClick = onConnect,
                enabled = connection != ObdConnectionState.CONNECTING,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DashColors.Accent,
                    contentColor = DashColors.Background
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (connected) "Reconnect" else "Connect")
            }
        }
    }
}

@Composable
private fun HudStat(label: String, value: String, unit: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = valueColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(text = unit, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Text(text = label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
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
                    .padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
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
                        DrawerApp(app = app, onClick = { onLaunch(app) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerApp(app: AppEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(DashColors.CardHi),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(icon = app.icon, size = 42.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = app.label,
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

/** Launches the device's voice assistant, if one is available. */
private fun launchAssistant(context: Context) {
    val actions = listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)
    for (action in actions) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
}

private fun requiredBluetoothPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        emptyList()
    }

/** Connects to a paired ELM327 adapter, or opens Bluetooth settings to pair one. */
private suspend fun connectOrOpenSettings(context: Context) {
    val address = findObdDeviceAddress(context)
    if (address != null) {
        ObdBluetoothManager.connect(address)
    } else {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@SuppressLint("MissingPermission")
private fun findObdDeviceAddress(context: Context): String? {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
    val adapter = manager.adapter ?: return null
    val bonded = try {
        adapter.bondedDevices
    } catch (e: SecurityException) {
        null
    } ?: return null

    return bonded.firstOrNull { device ->
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        }.orEmpty()
        name.contains("OBD", ignoreCase = true) ||
            name.contains("ELM", ignoreCase = true) ||
            name.contains("327", ignoreCase = true)
    }?.address
}
