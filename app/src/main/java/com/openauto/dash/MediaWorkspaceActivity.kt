package com.openauto.dash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Secondary ("right") activity of the Drive workspace: a compact now-playing
 * screen. Embedded beside [MapsWorkspaceActivity] by the split rule in
 * [DashApplication].
 */
class MediaWorkspaceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenAutoDashTheme {
                WorkspaceMediaScreen()
            }
        }
    }
}

private object MediaColors {
    val Background = Color(0xFF0B0C0F)
    val Card = Color(0xFF1E2024)
    val CardHi = Color(0xFF2A2D33)
    val Accent = Color(0xFF8AB4F8)
    val Muted = Color(0xFF9AA0A6)
    val TextPrimary = Color(0xFFE8EAED)
    val TextSecondary = Color(0xFF9AA0A6)
}

@Composable
private fun WorkspaceMediaScreen() {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MediaColors.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MediaColors.CardHi),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MediaColors.TextSecondary,
                modifier = Modifier.size(64.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "NOW PLAYING",
            color = MediaColors.Accent,
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
            color = MediaColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = mediaState.artist.ifBlank { if (hasAccess) "—" else "Tap to enable" },
            color = MediaColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )

        if (hasAccess) {
            if (mediaState.durationMs > 0L) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MediaColors.Accent,
                    trackColor = MediaColors.CardHi,
                    drawStopIndicator = {}
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.previous() }, modifier = Modifier.size(60.dp)) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MediaColors.TextPrimary,
                        modifier = Modifier.size(38.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                FilledIconButton(
                    onClick = { controller.playPause() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MediaColors.Accent,
                        contentColor = MediaColors.Background
                    )
                ) {
                    Icon(
                        imageVector = if (mediaState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(44.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                IconButton(onClick = { controller.next() }, modifier = Modifier.size(60.dp)) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = MediaColors.TextPrimary,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
        } else {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { CarMediaController.openNotificationAccessSettings(context) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MediaColors.Accent,
                    contentColor = MediaColors.Background
                )
            ) {
                Text("Grant Media Access")
            }
        }
    }
}
