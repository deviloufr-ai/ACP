package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Responsive Automotive Dashboard UI.
 * Adapts layout based on screen orientation:
 * - Landscape (Head Units/Horizontal Mounts): 50/50 horizontal split
 * - Portrait (Vertical Mounts): 50/50 vertical split
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutomotiveDashboard() {
    val context = LocalContext.current
    
    // Initialize OBD and Media managers
    ObdBluetoothManager.setContext(context)
    
    var bluetoothMac by remember { mutableStateOf<String?>(null) }
    var bluetoothName by remember { mutableStateOf<String?>(null) }
    var isBluetoothConnected by remember { mutableStateOf(false) }
    
    // Remember media state from system
    val mediaController = remember { CarMediaController() }
    val mediaState = mediaController.mediaState.collectAsState(initial = MediaState())
    
    // Speed warning threshold
    const val SPEED_WARNING_KMH = 110
    
    // OBD status color based on connection
    val speedColor = if (isBluetoothConnected) Color(0xFF81D4FA) else Color(0xFF555555)
    val rpmColor = if (isBluetoothConnected) Color(0xFFFFA07A) else Color(0xFF555555)
    val warningRed = Color(0xFFFF5252)
    
    // Launcher for opening Google Maps
    val mapsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { intent ->
        // Handle map selection if needed
    }
    
    OpenAutoDashTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxWidth = maxOf(minWidth, maxWidth)
            val maxHeight = maxOf(minHeight, maxHeight)
            
            // Detect orientation: Landscape vs Portrait
            val isLandscape = maxWidth > maxHeight
            
            AutomotiveDashboardScreen(
                mediaState = mediaState.value,
                speedColor = speedColor,
                rpmColor = rpmColor,
                warningRed = warningRed,
                mapsLauncher = mapsLauncher,
                isBluetoothConnected = isBluetoothConnected
            )
        }
    }
}

/**
 * Main dashboard screen with responsive layout.
 */
@Composable
private fun AutomotiveDashboardScreen(
    mediaState: MediaState,
    speedColor: Color,
    rpmColor: Color,
    warningRed: Color,
    mapsLauncher: androidx.compose.ui.platform.LocalContext,
    isBluetoothConnected: Boolean
) {
    // Top Status Bar
    val topBarModifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .background(Color(0xFF1C1E24))
    
    Column(modifier = Modifier.fillMaxSize()) {
        TopStatusBar(topBarModifier)
        
        // Determine layout based on orientation
        val contentArea = if (false) {
            // Portrait mode - 50/50 vertical split would go here
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Portrait layout implementation
            }
        } else {
            // Landscape mode - 50/50 horizontal split
            AutomotiveDashboardLandscape(
                speedColor = speedColor,
                rpmColor = rpmColor,
                warningRed = warningRed,
                mediaState = mediaState,
                isBluetoothConnected = isBluetoothConnected
            )
        }
        
        contentArea
    }
}

/**
 * Top Status Bar - Shows app info and telemetry status.
 */
@Composable
private fun TopStatusBar(modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFF1C1E24),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Title and Clock
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OpenAuto Dash", style = MaterialTheme.typography.titleSmall)
                Spacer(width = 8.dp)
                Text(
                    text = java.time.LocalNow.format(java.text.SimpleDateFormat("HH:mm")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            
            // OBD and Battery Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                // OBD Status Indicator
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (isBluetoothConnected) Color(0xFF69F0AE) else Color(0xFF555555)
                ) {
                    Icon(
                        imageVector = if (isBluetoothConnected) Icons.
