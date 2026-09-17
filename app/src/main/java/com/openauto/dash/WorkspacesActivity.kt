package com.openauto.dash

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Swipeable "virtual desktops". Each page is a two-pane workspace of the app's
 * own content, and swiping horizontally moves between them:
 *
 *  - Workspace 1 (Drive):   Maps | now-playing media
 *  - Workspace 2 (Vehicle): OBD telemetry | climate
 *
 * This is the reliable way to get the swipe-between-workspaces experience:
 * Activity Embedding lays out whole activities at the window level, so its
 * splits can't be nested inside a pager — a Compose [HorizontalPager] of
 * two-pane rows delivers the "ViewPager2 of workspaces" UX with our own panels.
 */
class WorkspacesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            OpenAutoDashTheme {
                WorkspacesPager()
            }
        }
    }
}

private object WsColors {
    val Background = Color(0xFF0B0C0F)
    val Card = Color(0xFF1E2024)
    val CardHi = Color(0xFF2A2D33)
    val Accent = Color(0xFF8AB4F8)
    val Rpm = Color(0xFFF6AD7B)
    val Warning = Color(0xFFF28B82)
    val Good = Color(0xFF81C995)
    val Muted = Color(0xFF9AA0A6)
    val TextPrimary = Color(0xFFE8EAED)
    val TextSecondary = Color(0xFF9AA0A6)
}

private const val SPEED_WARNING = 110
private val WORKSPACE_NAMES = listOf("Drive", "Vehicle")

@Composable
private fun WorkspacesPager() {
    val pagerState = rememberPagerState(pageCount = { WORKSPACE_NAMES.size })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WsColors.Background)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (page) {
                    0 -> {
                        MapsPane(Modifier.weight(1f).fillMaxHeight())
                        WorkspaceMediaPane(Modifier.weight(1f).fillMaxHeight())
                    }
                    else -> {
                        WorkspaceTelemetryPane(Modifier.weight(1f).fillMaxHeight())
                        WorkspaceClimatePane(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }

        WorkspaceIndicator(current = pagerState.currentPage, count = WORKSPACE_NAMES.size)
    }
}

@Composable
private fun WorkspaceIndicator(current: Int, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp, top = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = WORKSPACE_NAMES.getOrElse(current) { "" },
            color = WsColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.width(12.dp))
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (index == current) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (index == current) WsColors.Accent else WsColors.CardHi)
            )
        }
    }
}

@Composable
private fun MapsPane(modifier: Modifier = Modifier) {
    // No rounded clip: a clipped hardware WebView renders black on some head unit GPUs.
    Box(modifier = modifier.background(WsColors.Card)) {
        MapsPanel(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun WorkspaceMediaPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { CarMediaController(context) }
    val mediaState by controller.mediaState.collectAsState()
    val hasAccess = CarMediaController.hasNotificationAccess(context)

    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.stop() }
    }

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

    WsCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(WsColors.CardHi),
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
                        tint = WsColors.TextSecondary,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "NOW PLAYING",
                color = WsColors.Accent,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    mediaState.hasMedia && mediaState.title.isNotBlank() -> mediaState.title
                    hasAccess -> "Nothing playing"
                    else -> "Media access needed"
                },
                color = WsColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = mediaState.artist.ifBlank { if (hasAccess) "—" else "Tap to enable" },
                color = WsColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )

            if (hasAccess) {
                if (mediaState.durationMs > 0L) {
                    Spacer(Modifier.height(18.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = WsColors.Accent,
                        trackColor = WsColors.CardHi,
                        drawStopIndicator = {}
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", tint = WsColors.TextPrimary, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    FilledIconButton(
                        onClick = { controller.playPause() },
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = WsColors.Accent,
                            contentColor = WsColors.Background
                        )
                    ) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    IconButton(onClick = { controller.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipNext, "Next", tint = WsColors.TextPrimary, modifier = Modifier.size(36.dp))
                    }
                }
            } else {
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { CarMediaController.openNotificationAccessSettings(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WsColors.Accent,
                        contentColor = WsColors.Background
                    )
                ) {
                    Text("Grant Media Access")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTelemetryPane(modifier: Modifier = Modifier) {
    val obdData by ObdBluetoothManager.data.collectAsState()
    val connection by ObdBluetoothManager.connectionState.collectAsState()
    val connected = connection == ObdConnectionState.CONNECTED

    WsCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (connected) obdData.speedKmh.toString() else "--",
                color = when {
                    !connected -> WsColors.Muted
                    obdData.speedKmh > SPEED_WARNING -> WsColors.Warning
                    else -> WsColors.Accent
                },
                fontSize = 88.sp,
                fontWeight = FontWeight.Bold
            )
            Text("km/h", color = WsColors.TextSecondary, style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TelemetryStat("RPM", if (connected) obdData.rpm.toString() else "--", if (connected) WsColors.Rpm else WsColors.Muted)
                TelemetryStat("Coolant", if (connected) "${obdData.coolantTempC}°" else "--", if (connected) WsColors.Good else WsColors.Muted)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = when (connection) {
                    ObdConnectionState.CONNECTED -> "OBD connected"
                    ObdConnectionState.CONNECTING -> "Connecting…"
                    ObdConnectionState.ERROR -> "OBD error"
                    ObdConnectionState.DISCONNECTED -> "Not connected — connect from the home screen"
                },
                color = WsColors.Muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun TelemetryStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = valueColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = WsColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun WorkspaceClimatePane(modifier: Modifier = Modifier) {
    // Local UI state only: standard Android exposes no HVAC control API (that
    // needs Android Automotive's CarPropertyManager), so this is a demo panel.
    var temperature by remember { mutableIntStateOf(21) }
    var fanLevel by remember { mutableIntStateOf(2) }
    var acOn by remember { mutableStateOf(true) }

    WsCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("CLIMATE", color = WsColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                ClimateRoundButton(Icons.Filled.Remove, "Cooler") {
                    if (temperature > 16) temperature--
                }
                Spacer(Modifier.width(20.dp))
                Text("$temperature°", color = WsColors.TextPrimary, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(20.dp))
                ClimateRoundButton(Icons.Filled.Add, "Warmer") {
                    if (temperature < 28) temperature++
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Air, contentDescription = "Fan", tint = WsColors.TextSecondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                repeat(5) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = 14.dp, height = if (index < fanLevel) 26.dp else 14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (index < fanLevel) WsColors.Accent else WsColors.CardHi)
                            .clickable { fanLevel = index + 1 }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { acOn = !acOn },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (acOn) WsColors.Accent else WsColors.CardHi,
                    contentColor = if (acOn) WsColors.Background else WsColors.TextSecondary
                )
            ) {
                Icon(Icons.Filled.AcUnit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (acOn) "A/C On" else "A/C Off")
            }

            Spacer(Modifier.height(12.dp))
            Text("Demo panel — no HVAC API on standard Android", color = WsColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ClimateRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = WsColors.CardHi,
            contentColor = WsColors.TextPrimary
        )
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun WsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = WsColors.Card,
        shape = RoundedCornerShape(24.dp),
        content = content
    )
}
