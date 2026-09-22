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
        val error: String? = null,
        /** "pinned" or "freeform": how the window is floating. */
        val mode: String? = null,
        /** One entry per stack the system reported, for on-tile diagnostics. */
        val seen: String? = null
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** Screen-pixel rectangle; a plain data class so the parser is JVM-testable. */
    data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * A floating window as reported by `am stack list`: either a pinned
     * (picture-in-picture) stack or, as this ROM prefers, a freeform one.
     */
    data class FloatingWindow(
        val stackId: Int,
        val taskId: Int?,
        val packageName: String,
        val bounds: ScreenRect?,
        val mode: String
    )

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
            val lookup = runCatching { findFloatingWindow(context) }
            val stack = lookup.getOrNull()
            if (lookup.isFailure) {
                publishError(lookup.exceptionOrNull()!!)
                lastApplied = null; lastStack = null
            } else if (stack == null) {
                _status.value = Status(seen = lastSeen)
                lastApplied = null; lastStack = null
            } else {
                _status.value = Status(pipPackage = stack.packageName, docked = stack.bounds == rect, mode = stack.mode, seen = lastSeen)
                val drifted = stack.bounds != rect || lastApplied != rect || lastStack != stack.stackId
                if (drifted) {
                    runCatching { resize(context, stack, rect) }
                        .onSuccess {
                            lastApplied = rect; lastStack = stack.stackId
                            _status.value = Status(pipPackage = stack.packageName, docked = true, mode = stack.mode, seen = lastSeen)
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
            val stack = runCatching { findFloatingWindow(context) }.getOrNull() ?: return@launch
            val dm = context.resources.displayMetrics
            val w = dm.widthPixels / 4
            val h = w * 9 / 16
            val margin = (12 * dm.density).roundToInt()
            val rect = ScreenRect(dm.widthPixels - w - margin, dm.heightPixels - h - margin, dm.widthPixels - margin, dm.heightPixels - margin)
            runCatching { resize(context, stack, rect) }.onFailure { Log.w(TAG, "park failed", it) }
            closeConnection()
        }
    }

    /** Summary of the last stack listing, e.g. "fullscreen dash · freeform maps". */
    @Volatile private var lastSeen: String? = null
    private var lastListing: String? = null

    private suspend fun findFloatingWindow(context: Context): FloatingWindow? {
        val listing = shell(context, "am stack list")
        if (listing != lastListing) {
            // Full dump once per change: this is what tells us how the ROM
            // reports its floating windows.
            Log.i(TAG, "am stack list:\n$listing")
            lastListing = listing
        }
        lastSeen = summarizeStacks(listing)
        return parseFloatingWindow(listing, context.packageName)
    }

    private suspend fun resize(context: Context, win: FloatingWindow, rect: ScreenRect) {
        val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        val attempts = if (win.mode == "pinned") {
            // Android 10/11 accept both; the animated form is nicer when present.
            listOf("am stack resize-animated ${win.stackId} $bounds", "am stack resize ${win.stackId} $bounds")
        } else {
            listOfNotNull(
                win.taskId?.let { "am task resize $it $bounds" },
                "am stack resize ${win.stackId} $bounds"
            )
        }
        var last = ""
        for (cmd in attempts) {
            last = shell(context, cmd)
            if (!looksLikeError(last)) {
                Log.d(TAG, "${win.mode} ${win.packageName} -> $bounds via `$cmd`")
                return
            }
            Log.w(TAG, "`$cmd` failed: ${last.trim()}")
        }
        error(last.trim().lines().firstOrNull().orEmpty().ifBlank { "resize refused" })
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
     * Picks the floating window out of `am stack list` output. Each stack is a
     * block starting with `Stack id=N`; its configuration names the windowing
     * mode (`mWindowingMode=pinned` / `freeform`) and its tasks appear as
     * `taskId=N: package/activity`. Pinned wins over freeform; our own package
     * and the Home stack are never candidates.
     */
    internal fun parseFloatingWindow(output: String, selfPackage: String = "com.openauto.dash"): FloatingWindow? {
        val found = mutableListOf<FloatingWindow>()
        for (block in stackBlocks(output)) {
            val mode = windowingMode(block) ?: continue
            if (mode != "pinned" && mode != "freeform") continue
            if (block.contains("ActivityType=home")) continue
            val id = block.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
            val task = TASK.find(block) ?: continue
            val pkg = task.groupValues[2]
            if (pkg == selfPackage) continue
            val b = BOUNDS.find(block)?.groupValues
            val bounds = b?.let { ScreenRect(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt()) }
            found += FloatingWindow(id, task.groupValues[1].toIntOrNull(), pkg, bounds, mode)
        }
        return found.firstOrNull { it.mode == "pinned" } ?: found.firstOrNull()
    }

    /** "mode package" per stack, for the tile's diagnostic line. */
    internal fun summarizeStacks(output: String): String? {
        val parts = stackBlocks(output).mapNotNull { block ->
            val mode = windowingMode(block) ?: return@mapNotNull null
            val pkg = TASK.find(block)?.groupValues?.get(2) ?: "(empty)"
            mode + " " + pkg.substringAfterLast('.')
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00b7 ")
    }

    private val TASK = Regex("taskId=(\\d+): ([\\w.]+)/")
    private val BOUNDS = Regex("bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
    private val MODE_NAME = Regex("(?:indowingMode|winMode)=([a-z-]+)")
    private val MODE_NUMBER = Regex("indowingMode=(\\d)")

    private fun stackBlocks(output: String): List<String> =
        output.split(Regex("(?m)^\\s*Stack id=")).drop(1)

    private fun windowingMode(block: String): String? {
        MODE_NAME.find(block)?.let { return it.groupValues[1] }
        // Some builds print the numeric mode: 1 fullscreen, 2 pinned, 5 freeform.
        MODE_NUMBER.find(block)?.let {
            return when (it.groupValues[1]) { "1" -> "fullscreen"; "2" -> "pinned"; "5" -> "freeform"; else -> "other" }
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
                    pkg != null && status.docked -> "Docked: ${pkg.substringAfterLast('.')} (${status.mode})"
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
            status.seen?.let { seen ->
                Spacer(Modifier.height(6.dp))
                Text("Windows: $seen", color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
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
