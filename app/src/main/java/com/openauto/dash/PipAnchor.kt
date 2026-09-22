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
 * Docks the system picture-in-picture window (Google Maps guidance after
 * pressing Home) onto a dashboard tile.
 *
 * No app can embed another app's PiP window: SystemUI draws it above
 * everything. What we *can* do on this head unit is tell the system where the
 * pinned stack should be, through the internal ADB socket (`am stack resize`,
 * a shell-uid command, no root needed). The [PipAnchorCard] measures its own
 * screen rectangle and, while it is on screen, keeps the PiP window sized and
 * positioned to match; when the tile scrolls away the window is parked in a
 * corner, since PiP cannot be hidden without leaving it.
 */
object PipAnchor {

    private const val TAG = "PipAnchor"
    private const val POLL_MS = 2_500L

    /** What the tile shows. [pipPackage] is null while no PiP window exists. */
    data class Status(
        val pipPackage: String? = null,
        val docked: Boolean = false,
        val error: String? = null
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** Screen-pixel rectangle; a plain data class so the parser is JVM-testable. */
    data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** A pinned (PiP) stack as reported by `am stack list`. */
    data class PinnedStack(val stackId: Int, val packageName: String, val bounds: ScreenRect?)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val io = Mutex()
    private var dadb: Dadb? = null

    /**
     * Keeps the PiP window on [rect] (screen pixels) until cancelled: finds the
     * pinned stack every few seconds and resizes it whenever it drifted.
     */
    suspend fun track(context: Context, rect: ScreenRect) {
        var lastApplied: ScreenRect? = null
        var lastStack: Int? = null
        while (true) {
            val lookup = runCatching { findPinnedStack(context) }
            val stack = lookup.getOrNull()
            if (lookup.isFailure) {
                publishError(lookup.exceptionOrNull()!!)
                lastApplied = null; lastStack = null
            } else if (stack == null) {
                _status.value = Status()
                lastApplied = null; lastStack = null
            } else {
                _status.value = Status(pipPackage = stack.packageName, docked = stack.bounds == rect)
                val drifted = stack.bounds != rect || lastApplied != rect || lastStack != stack.stackId
                if (drifted) {
                    runCatching { resize(context, stack.stackId, rect) }
                        .onSuccess {
                            lastApplied = rect; lastStack = stack.stackId
                            _status.value = Status(pipPackage = stack.packageName, docked = true)
                        }
                        .onFailure { publishError(it) }
                }
            }
            delay(POLL_MS)
        }
    }

    /**
     * The tile left the screen: move the window out of the way, to a small
     * rectangle in the bottom-right corner of the display.
     */
    fun park(context: Context) {
        scope.launch {
            val stack = runCatching { findPinnedStack(context) }.getOrNull() ?: return@launch
            val dm = context.resources.displayMetrics
            val w = dm.widthPixels / 4
            val h = w * 9 / 16
            val margin = (12 * dm.density).roundToInt()
            val rect = ScreenRect(dm.widthPixels - w - margin, dm.heightPixels - h - margin, dm.widthPixels - margin, dm.heightPixels - margin)
            runCatching { resize(context, stack.stackId, rect) }.onFailure { Log.w(TAG, "park failed", it) }
            closeConnection()
        }
    }

    private suspend fun findPinnedStack(context: Context): PinnedStack? =
        parsePinnedStack(shell(context, "am stack list"))

    private suspend fun resize(context: Context, stackId: Int, rect: ScreenRect) {
        val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        // Android 10/11 accept both; the animated form is nicer when present.
        var out = shell(context, "am stack resize-animated $stackId $bounds")
        if (looksLikeError(out)) out = shell(context, "am stack resize $stackId $bounds")
        if (looksLikeError(out)) {
            Log.w(TAG, "resize failed: ${out.trim()}")
            error(out.trim().lines().first())
        }
        Log.d(TAG, "PiP stack $stackId -> $bounds")
    }

    private fun looksLikeError(out: String): Boolean =
        out.contains("Error", ignoreCase = true) || out.contains("Exception") || out.contains("Unknown")

    /** How shell commands reach the system: root via Magisk, or the ADB socket. */
    private enum class Backend { SU, ADB }
    private var backend: Backend? = null

    private suspend fun shell(context: Context, cmd: String): String = withContext(Dispatchers.IO) {
        io.withLock {
            val chosen = backend ?: (if (SystemInstaller.isRootAvailable()) Backend.SU else Backend.ADB)
                .also { backend = it; Log.i(TAG, "shell backend: $it") }
            when (chosen) {
                Backend.SU -> suShell(cmd)
                Backend.ADB -> adbShell(context, cmd)
            }
        }
    }

    /** `su -c cmd`, bounded so a stuck root prompt can't pin the poller. */
    private fun suShell(cmd: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        process.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { out.append(process.inputStream.bufferedReader().readText()) }
        val errReader = Thread { out.append(process.errorStream.bufferedReader().readText()) }
        reader.start(); errReader.start()
        if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy()
            throw IllegalStateException("su timed out")
        }
        reader.join(1000); errReader.join(1000)
        if (process.exitValue() != 0 && out.isBlank()) throw IllegalStateException("su exit ${process.exitValue()}")
        return out.toString()
    }

    private fun adbShell(context: Context, cmd: String): String {
        val conn = dadb ?: AdbInstaller.connect(context, adbPort()).also { dadb = it }
        try {
            val res = conn.shell(cmd)
            return res.output + res.errorOutput
        } catch (e: Exception) {
            closeConnection()
            throw e
        }
    }

    /** The unit's ADB TCP port from `service.adb.tcp.port`, else the K706 default. */
    private fun adbPort(): Int = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
        p.inputStream.bufferedReader().readText().trim().toIntOrNull()
    }.getOrNull() ?: AdbInstaller.DEFAULT_PORT

    private fun closeConnection() {
        runCatching { dadb?.close() }
        dadb = null
    }

    private fun publishError(e: Throwable) {
        Log.w(TAG, "PiP anchor error", e)
        val msg = when {
            e is java.net.ConnectException || e.message?.contains("Connection refused") == true ->
                "No root (Magisk) and the ADB socket on port ${adbPort()} isn't listening"
            else -> e.message ?: e.javaClass.simpleName
        }
        _status.value = _status.value.copy(error = msg)
    }

    /**
     * Picks the pinned stack out of `am stack list` output. Each stack is a block
     * starting with `Stack id=N`; the pinned one says `mWindowingMode=pinned` in
     * its configuration and lists its task as `taskId=N: package/activity`.
     */
    internal fun parsePinnedStack(output: String): PinnedStack? {
        val blocks = output.split(Regex("(?m)^\\s*Stack id=")).drop(1)
        for (block in blocks) {
            if (!block.contains("indowingMode=pinned") && !block.contains("winMode=pinned")) continue
            val id = block.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
            val pkg = Regex("taskId=\\d+: ([\\w.]+)/").find(block)?.groupValues?.get(1) ?: continue
            val b = Regex("bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(block)?.groupValues
            val bounds = b?.let { ScreenRect(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt()) }
            return PinnedStack(id, pkg, bounds)
        }
        return null
    }
}

/**
 * "Maps window" tile: while it is on screen the floating PiP window is kept
 * exactly over it; swiping to another page parks the window in a corner.
 */
@Composable
internal fun PipAnchorCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val status by PipAnchor.status.collectAsState()

    var target by remember { mutableStateOf<PipAnchor.ScreenRect?>(null) }
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            PipAnchor.park(context)
        }
    }

    // Re-target after the tile settles: a page swipe or a drag in edit mode
    // moves it many times per second, and each ADB round trip costs real time.
    LaunchedEffect(target, resumed) {
        val rect = target ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        delay(350)
        PipAnchor.track(context, rect)
    }

    Card(
        modifier = modifier.onGloballyPositioned { coords ->
            val b = coords.boundsInRoot()
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            val r = PipAnchor.ScreenRect(
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
            Text("MAPS WINDOW", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            val pkg = status.pipPackage
            val err = status.error
            Text(
                text = when {
                    pkg != null && status.docked -> "Docked: ${pkg.substringAfterLast('.')}"
                    pkg != null -> "Moving the window here…"
                    err != null -> err
                    else -> "The floating Maps window docks here.\nStart guidance in Google Maps, then press Home."
                },
                color = when {
                    err != null && pkg == null -> DashColors.Warning
                    pkg != null -> DashColors.Good
                    else -> DashColors.TextSecondary
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            if (pkg == null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0"))
                        context.launchSafely(launch)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) { Text("Open Google Maps") }
            }
        }
    }
}
