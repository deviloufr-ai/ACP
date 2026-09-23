package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * "Maps window" tile: while it is on screen the floating PiP window is kept
 * exactly over it; swiping to another page parks the window in a corner.
 */
@Composable
internal fun PipAnchorCard(
    modifier: Modifier = Modifier,
    isDock: Boolean = false,
    packageName: String = PipAnchor.MAPS_PACKAGE,
    appLabel: String = "Maps",
    /** Called with the window's pixel size when the system makes it larger than the tile. */
    onWindowBiggerThanTile: ((Int, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    // With a permanent Maps dock on screen, a "Maps window" tile on a page must
    // not compete for the same window: it just points at the dock.
    val dockActive by PipAnchor.dockActive.collectAsState()
    val isMaps = packageName == PipAnchor.MAPS_PACKAGE
    if (isDock) {
        DisposableEffect(Unit) {
            PipAnchor.dockActive.value = true
            onDispose { PipAnchor.dockActive.value = false }
        }
    } else if (dockActive && isMaps) {
        Card(modifier = modifier) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.apps_window_title, "MAPS"), color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.apps_window_maps_docked_beside), color = DashColors.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val status by PipAnchor.statusOf(packageName).collectAsState()
    // An app's minimum window size can exceed the tile; let the tile grow to it
    // rather than have the window spill over its neighbours.
    LaunchedEffect(status.oversizePx) {
        val (w, h) = status.oversizePx ?: return@LaunchedEffect
        onWindowBiggerThanTile?.invoke(w, h)
    }
    // Counted while composed, so the window is never mistaken for a stray while
    // the tracking loop is between restarts.
    DisposableEffect(packageName) {
        PipAnchor.tileShown(packageName)
        onDispose { PipAnchor.tileHidden(packageName) }
    }

    var target by remember { mutableStateOf<ScreenRect?>(null) }
    var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { started = true; PipAnchor.expectReturn(packageName) }
                // Another app took the whole screen: stop polling and park the
                // window aside, still running; coming back, track() docks it
                // again. (Touching the Maps window only *pauses* the launcher,
                // which must not hide anything.)
                Lifecycle.Event.ON_STOP -> { started = false; PipAnchor.parkForOtherApp(context, packageName) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // A Maps page tile standing down for the dock must not close the very
            // window the dock is about to take over.
            val handingOverToDock = isMaps && !isDock && PipAnchor.dockActive.value
            if (!handingOverToDock) PipAnchor.hide(context, packageName)
        }
    }

    // Re-target after the tile settles: a page swipe or a drag in edit mode
    // moves it many times per second, and each ADB round trip costs real time.
    val steppedAside by PipAnchor.steppedAside.collectAsState()
    LaunchedEffect(target, started, steppedAside) {
        val rect = target ?: return@LaunchedEffect
        if (!started) return@LaunchedEffect
        if (steppedAside) {
            PipAnchor.parkAside(context, packageName)
            return@LaunchedEffect
        }
        delay(350)
        PipAnchor.track(context, rect, packageName)
    }

    // The skin's frame (a round porthole, a chrome bezel...) over the docked Maps
    // window, only while it actually sits here and the dashboard is on screen.
    WindowFrameOverlay(
        bounds = status.windowBounds.takeIf { isMaps && started && !steppedAside && status.docked && status.pipPackage != null }
    )

    Card(
        modifier = modifier.onGloballyPositioned { coords ->
            val b = coords.boundsInRoot()
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            val r = ScreenRect(
                (b.left + origin[0]).roundToInt(), (b.top + origin[1]).roundToInt(),
                (b.right + origin[0]).roundToInt(), (b.bottom + origin[1]).roundToInt()
            )
            if (r != target) target = r
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.apps_window_title, appLabel.uppercase()), color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            val pkg = status.pipPackage
            val err = status.error
            val name = pkg?.substringAfterLast('.')
            Text(
                text = when {
                    // The mode ("freeform" / "pinned") is the system's own term, shown as is.
                    pkg != null && status.docked -> stringResource(R.string.apps_window_docked, name.orEmpty(), status.mode.orEmpty())
                    pkg != null && status.gaveUp -> stringResource(R.string.apps_window_gave_up, name.orEmpty())
                    pkg != null -> stringResource(R.string.apps_window_moving, name.orEmpty())
                    PipAnchor.autoOpen(context, packageName) -> stringResource(R.string.apps_window_opening, appLabel)
                    isMaps -> stringResource(R.string.apps_window_maps_hint)
                    else -> stringResource(R.string.apps_window_app_hint, appLabel)
                },
                color = when {
                    pkg != null && status.docked -> DashColors.Good
                    pkg != null && status.gaveUp -> DashColors.Warning
                    else -> DashColors.TextSecondary
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            if (err != null) {
                Spacer(Modifier.height(4.dp))
                Text(err, color = DashColors.Warning, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
            // Diagnostics while not docked: where the window is vs. where it should be,
            // and what the last command said. Readable without adb.
            if (pkg != null && !status.docked) {
                Spacer(Modifier.height(4.dp))
                val at = status.windowBounds?.let { "[${it.left},${it.top} ${it.right},${it.bottom}]" } ?: "?"
                val to = status.target?.let { "[${it.left},${it.top} ${it.right},${it.bottom}]" } ?: "?"
                Text(stringResource(R.string.apps_window_diag_position, at, to), color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                status.lastResult?.let {
                    Text(stringResource(R.string.apps_window_diag_last, it), color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
            status.seen?.let { seen ->
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.apps_window_diag_seen, seen), color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
            // Full Maps UI in a window sized to this tile (a freeform task), the
            // way the head unit's stock launcher shows it. Offered whenever the
            // docked window is not already that.
            if (status.mode != "freeform") {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val rect = target
                        val bounds = rect?.let { android.graphics.Rect(it.left, it.top, it.right, it.bottom) }
                        PipAnchor.setAutoOpen(context, true, packageName)
                        if (!SplitLauncher.launchFreeform(context, packageName, bounds)) {
                            context.packageManager.getLaunchIntentForPackage(packageName)?.let { context.launchSafely(it) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) { Text(if (pkg == null) stringResource(R.string.apps_window_open, appLabel) else stringResource(R.string.apps_window_open_full, appLabel)) }
            }
        }
    }
}
