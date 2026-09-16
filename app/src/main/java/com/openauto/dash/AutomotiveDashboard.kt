package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Central color palette for the dashboard (dark, high-contrast). */
private object DashColors {
    val Background = Color(0xFF0F1115)
    val Surface = Color(0xFF1C1E24)
    val Hud = Color(0xFF2A2D35)
    val Speed = Color(0xFF81D4FA)
    val Rpm = Color(0xFFFFA07A)
    val Warning = Color(0xFFFF5252)
    val Good = Color(0xFF69F0AE)
    val Muted = Color(0xFF555B63)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFC4C9CE)
    val Primary = Color(0xFF6750A4)
}

private const val SPEED_WARNING_KMH = 110

/**
 * Responsive automotive dashboard.
 *
 * Landscape (head units / horizontal mounts): 50/50 left-right split.
 * Portrait (vertical phone mounts): 50/50 top-bottom split.
 */
@Composable
fun AutomotiveDashboard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mediaController = remember { CarMediaController(context) }
    val obdData by ObdBluetoothManager.data.collectAsState()
    val obdConnection by ObdBluetoothManager.connectionState.collectAsState()
    val mediaState by mediaController.mediaState.collectAsState()

    DisposableEffect(Unit) {
        ObdBluetoothManager.setContext(context)
        mediaController.start()
        onDispose { mediaController.stop() }
    }

    // Poll telemetry while connected.
    LaunchedEffect(obdConnection) {
        while (obdConnection == ObdConnectionState.CONNECTED) {
            ObdBluetoothManager.poll()
            delay(500)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DashColors.Background)
    ) {
        TopStatusBar(obdConnection = obdConnection)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth >= maxHeight) {
                // Landscape: 50/50 left-right split.
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationPane(
                        obdData = obdData,
                        connection = obdConnection,
                        onConnect = onConnectObd,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    MediaPane(
                        mediaState = mediaState,
                        controller = mediaController,
                        context = context,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            } else {
                // Portrait: 50/50 top-bottom split.
                Column(modifier = Modifier.fillMaxSize()) {
                    NavigationPane(
                        obdData = obdData,
                        connection = obdConnection,
                        onConnect = onConnectObd,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    MediaPane(
                        mediaState = mediaState,
                        controller = mediaController,
                        context = context,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun TopStatusBar(obdConnection: ObdConnectionState) {
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
            .height(52.dp),
        color = DashColors.Surface,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "OpenAuto Dash",
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = clock,
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            ConnectionChip(obdConnection)
        }
    }
}

@Composable
private fun ConnectionChip(connection: ObdConnectionState) {
    val (label, color) = when (connection) {
        ObdConnectionState.CONNECTED -> "OBD Connected" to DashColors.Good
        ObdConnectionState.CONNECTING -> "Connecting…" to DashColors.Speed
        ObdConnectionState.ERROR -> "OBD Error" to DashColors.Warning
        ObdConnectionState.DISCONNECTED -> "OBD Off" to DashColors.Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (connection == ObdConnectionState.CONNECTED) {
                Icons.Filled.BluetoothConnected
            } else {
                Icons.Filled.Bluetooth
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun NavigationPane(
    obdData: ObdData,
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = connection == ObdConnectionState.CONNECTED
    val speedColor = when {
        !connected -> DashColors.Muted
        obdData.speedKmh > SPEED_WARNING_KMH -> DashColors.Warning
        else -> DashColors.Speed
    }

    Surface(modifier = modifier, color = DashColors.Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (connected) obdData.speedKmh.toString() else "--",
                color = speedColor,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "km/h",
                color = DashColors.TextSecondary,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                HudTile(
                    label = "RPM",
                    value = if (connected) obdData.rpm.toString() else "--",
                    valueColor = if (connected) DashColors.Rpm else DashColors.Muted
                )
                HudTile(
                    label = "Coolant",
                    value = if (connected) "${obdData.coolantTempC}°C" else "--",
                    valueColor = if (connected) DashColors.Speed else DashColors.Muted
                )
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onConnect,
                enabled = connection != ObdConnectionState.CONNECTING,
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.Primary)
            ) {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (connected) "Reconnect OBD" else "Connect OBD")
            }
        }
    }
}

@Composable
private fun HudTile(label: String, value: String, valueColor: Color) {
    Surface(
        color = DashColors.Hud,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, color = valueColor, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                text = label,
                color = DashColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun MediaPane(
    mediaState: MediaState,
    controller: CarMediaController,
    context: Context,
    modifier: Modifier = Modifier
) {
    val hasAccess = CarMediaController.hasNotificationAccess(context)

    Surface(modifier = modifier, color = DashColors.Surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DashColors.Hud),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = DashColors.TextSecondary,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

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
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = mediaState.artist.ifBlank { if (hasAccess) "—" else "Tap below to enable" },
                color = DashColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))

            if (hasAccess) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { controller.previous() },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = DashColors.TextPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = { controller.playPause() },
                        modifier = Modifier.size(72.dp),
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
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(
                        onClick = { controller.next() },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = DashColors.TextPrimary,
                            modifier = Modifier.size(40.dp)
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
