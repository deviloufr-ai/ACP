package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** Central color palette for the launcher (deep, low-glare, high-contrast). */
private object DashColors {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF15181E)
    val SurfaceHi = Color(0xFF1E222A)
    val Rail = Color(0xFF0E1116)
    val Stroke = Color(0xFF262B33)
    val Primary = Color(0xFF5B8DEF)
    val Accent = Color(0xFF2DD4BF)
    val Speed = Color(0xFF7CC4FF)
    val Rpm = Color(0xFFFFB27A)
    val Warning = Color(0xFFFF6B6B)
    val Good = Color(0xFF54E39B)
    val Muted = Color(0xFF6B7280)
    val TextPrimary = Color(0xFFF4F6F8)
    val TextSecondary = Color(0xFFAEB6C0)
}

private const val SPEED_WARNING_KMH = 110
private const val MAX_FAVORITES = 5

/**
 * Modern car launcher.
 *
 * Layout: a fixed left [AppRail] (up to five favorite apps + an "All apps"
 * button), the [MapsPanel] which auto-opens Google Maps, and a [RightPanel]
 * that shows either the driving home screen (media + telemetry) or the full
 * app drawer.
 *
 * Landscape (head units): rail | maps | right, side by side.
 * Portrait (phone mounts): rail | (maps stacked over right).
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

    // Installed apps are enumerated once; favorites seed the rail.
    val apps = remember { AppLauncher.loadApps(context) }
    val favorites = remember(apps) { AppLauncher.pickFavorites(apps, MAX_FAVORITES) }

    var showAllApps by remember { mutableStateOf(false) }
    var hasMediaAccess by remember { mutableStateOf(CarMediaController.hasNotificationAccess(context)) }

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

    // Poll telemetry while connected.
    LaunchedEffect(obdConnection) {
        while (obdConnection == ObdConnectionState.CONNECTED) {
            ObdBluetoothManager.poll()
            delay(500)
        }
    }

    // Check GitHub for a newer APK on launch.
    LaunchedEffect(Unit) {
        updateManager.checkForUpdate()
    }

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
        // Close the drawer on a successful launch; if the app has no launch
        // intent, leave it open so the user can pick another.
        if (AppLauncher.launch(context, app.packageName)) {
            showAllApps = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DashColors.Background)
    ) {
        TopStatusBar(
            obdConnection = obdConnection,
            speedKmh = if (obdConnection == ObdConnectionState.CONNECTED) obdData.speedKmh else null,
            versionName = updateManager.currentVersionName
        )

        UpdateBanner(
            status = updateStatus,
            onUpdate = onUpdate,
            onDismiss = { updateManager.dismiss() }
        )

        Row(modifier = Modifier.fillMaxSize()) {
            AppRail(
                favorites = favorites,
                showAllApps = showAllApps,
                onLaunch = onLaunchApp,
                onToggleAllApps = { showAllApps = !showAllApps }
            )

            val rightPanel: @Composable (Modifier) -> Unit = { mod ->
                RightPanel(
                    showAllApps = showAllApps,
                    apps = apps,
                    onLaunch = onLaunchApp,
                    onCloseDrawer = { showAllApps = false },
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

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= maxHeight) {
                    // Landscape: maps and right panel side by side.
                    Row(modifier = Modifier.fillMaxSize()) {
                        MapsPanel(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        rightPanel(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    // Portrait: maps stacked above the right panel.
                    Column(modifier = Modifier.fillMaxSize()) {
                        MapsPanel(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                        rightPanel(
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

// --- Top bar -----------------------------------------------------------------

@Composable
private fun TopStatusBar(obdConnection: ObdConnectionState, speedKmh: Int?, versionName: String) {
    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClock()
            delay(1000)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        color = DashColors.Surface,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "OpenAuto",
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = " Dash",
                    color = DashColors.Primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "v$versionName",
                    color = DashColors.Muted,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (speedKmh != null) {
                    Text(
                        text = "$speedKmh km/h",
                        color = if (speedKmh > SPEED_WARNING_KMH) DashColors.Warning else DashColors.Speed,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Text(
                    text = clock,
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(16.dp))
                ConnectionChip(obdConnection)
            }
        }
    }
}

@Composable
private fun ConnectionChip(connection: ObdConnectionState) {
    val (label, color) = when (connection) {
        ObdConnectionState.CONNECTED -> "OBD" to DashColors.Good
        ObdConnectionState.CONNECTING -> "Connecting" to DashColors.Speed
        ObdConnectionState.ERROR -> "OBD Error" to DashColors.Warning
        ObdConnectionState.DISCONNECTED -> "OBD Off" to DashColors.Muted
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (connection == ObdConnectionState.CONNECTED) {
                    Icons.Filled.BluetoothConnected
                } else {
                    Icons.Filled.Bluetooth
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(text = label, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

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

    Surface(color = DashColors.Primary, modifier = Modifier.fillMaxWidth()) {
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
                    tint = Color.White,
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
                    color = Color.White,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            when (status) {
                is UpdateStatus.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onUpdate(status.info) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = DashColors.Primary
                        )
                    ) {
                        Text("Update")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = Color.White
                        )
                    }
                }

                is UpdateStatus.Downloading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )

                else -> {}
            }
        }
    }
}

// --- Left app rail -----------------------------------------------------------

@Composable
private fun AppRail(
    favorites: List<AppEntry>,
    showAllApps: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onToggleAllApps: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(92.dp),
        color = DashColors.Rail,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Brand mark.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DashColors.Primary),
                contentAlignment = Alignment.Center
            ) {
                Text("OA", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(20.dp))

            favorites.forEach { app ->
                RailAppButton(app = app, onClick = { onLaunch(app) })
                Spacer(Modifier.height(14.dp))
            }

            Spacer(Modifier.weight(1f))

            // "All apps" toggle at the bottom of the rail.
            RailIconButton(
                selected = showAllApps,
                onClick = onToggleAllApps
            ) {
                Icon(
                    imageVector = Icons.Filled.Apps,
                    contentDescription = "All apps",
                    tint = if (showAllApps) Color.White else DashColors.TextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Text(
                text = "All apps",
                color = if (showAllApps) DashColors.TextPrimary else DashColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun RailAppButton(app: AppEntry, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DashColors.SurfaceHi)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(icon = app.icon, size = 40.dp)
    }
}

@Composable
private fun RailIconButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) DashColors.Primary else DashColors.SurfaceHi)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// --- Right panel (home / drawer) ---------------------------------------------

@Composable
private fun RightPanel(
    showAllApps: Boolean,
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onCloseDrawer: () -> Unit,
    mediaState: MediaState,
    controller: CarMediaController,
    hasMediaAccess: Boolean,
    context: Context,
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = DashColors.Surface) {
        if (showAllApps) {
            AppDrawer(apps = apps, onLaunch = onLaunch, onClose = onCloseDrawer)
        } else {
            HomePanel(
                mediaState = mediaState,
                controller = controller,
                hasMediaAccess = hasMediaAccess,
                context = context,
                obdData = obdData,
                connection = connection,
                onConnect = onConnect
            )
        }
    }
}

@Composable
private fun AppDrawer(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
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
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    DrawerApp(app = app, onClick = { onLaunch(app) })
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
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppIcon(icon = app.icon, size = 56.dp)
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

@Composable
private fun HomePanel(
    mediaState: MediaState,
    controller: CarMediaController,
    hasMediaAccess: Boolean,
    context: Context,
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DashColors.SurfaceHi),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = DashColors.TextSecondary,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = when {
                    mediaState.hasMedia && mediaState.title.isNotBlank() -> mediaState.title
                    hasAccess -> "Nothing playing"
                    else -> "Media access needed"
                },
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = mediaState.artist.ifBlank { if (hasAccess) "—" else "Tap below to enable" },
                color = DashColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            if (hasAccess) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { controller.previous() },
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = DashColors.TextPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = { controller.playPause() },
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = DashColors.Primary
                        )
                    ) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(
                        onClick = { controller.next() },
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = DashColors.TextPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
            } else {
                Button(
                    onClick = { CarMediaController.openNotificationAccessSettings(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Primary)
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
                valueColor = if (connected) DashColors.Accent else DashColors.Muted
            )
            Button(
                onClick = onConnect,
                enabled = connection != ObdConnectionState.CONNECTING,
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.Primary),
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
        Text(text = value, color = valueColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(text = unit, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// --- Shared building blocks --------------------------------------------------

/** Rounded surface with a subtle border used for the home cards. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = DashColors.Surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, DashColors.Stroke),
        tonalElevation = 2.dp,
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
