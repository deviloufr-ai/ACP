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
    const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val POLL_MS = 2_500L

    /** What the tile shows. [pipPackage] is null while no PiP window exists. */
    data class Status(
        val pipPackage: String? = null,
        val docked: Boolean = false,
        val error: String? = null,
        /** "pinned" or "freeform": how the window is floating. */
        val mode: String? = null,
        /** One entry per stack the system reported, for on-tile diagnostics. */
        val seen: String? = null,
        /** Where the system says the window is, and where the tile wants it. */
        val windowBounds: ScreenRect? = null,
        val target: ScreenRect? = null,
        /** Outcome of the last placement command, e.g. "am stack resize 3: ok". */
        val lastResult: String? = null,
        /** Set once the tile has stopped fighting a system that keeps moving the window back. */
        val gaveUp: Boolean = false
    )

    // One status per docked app: several tiles (Maps, YouTube Music, ...) can
    // each own a window at the same time.
    private val statuses = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Status>>()
    private fun statusFlow(packageName: String) = statuses.getOrPut(packageName) { MutableStateFlow(Status()) }
    fun statusOf(packageName: String): StateFlow<Status> = statusFlow(packageName)

    /** Packages currently docked as freeform windows (drives the status-bar inset). */
    val dockedPackages = MutableStateFlow<Set<String>>(emptySet())
    private val freeformNow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun noteFreeform(packageName: String, present: Boolean) {
        if (present) freeformNow.add(packageName) else freeformNow.remove(packageName)
        dockedPackages.value = freeformNow.toSet()
    }

    /** True while the "Maps left" layout's permanent dock is on screen. */
    val dockActive = MutableStateFlow(false)

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
        val mode: String,
        /** False when another stack (e.g. the dashboard's) covers it. */
        val visible: Boolean = true
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val io = Mutex()
    private var dadb: Dadb? = null

    /**
     * Keeps the PiP window on [rect] (screen pixels) until cancelled: finds the
     * pinned stack every few seconds and resizes it whenever it drifted.
     */
    suspend fun track(context: Context, rect: ScreenRect, packageName: String = MAPS_PACKAGE) {
        var attempts = 0
        var lastStack: Int? = null
        var lastResult: String? = null
        val status = statusFlow(packageName)
        while (true) {
            val lookup = runCatching { findFloatingWindow(context, packageName) }
            val win = lookup.getOrNull()
            if (lookup.isFailure) {
                publishError(packageName, lookup.exceptionOrNull()!!)
                attempts = 0; lastStack = null
            } else if (win == null) {
                status.value = Status(seen = lastSeen)
                attempts = 0; lastStack = null
                noteFreeform(packageName, false)
                if (freeformNow.isEmpty()) setDashboardFocusable(context, true)
                val now = System.currentTimeMillis()
                if (autoOpen(context, packageName) && now - (lastReopenAt[packageName] ?: 0L) > REOPEN_COOLDOWN_MS) {
                    lastReopenAt[packageName] = now
                    val bounds = android.graphics.Rect(rect.left, rect.top, rect.right, rect.bottom)
                    Log.i(TAG, "opening $packageName at $rect")
                    SplitLauncher.launchFreeform(context, packageName, bounds)
                }
            } else {
                if (win.stackId != lastStack) { attempts = 0; lastStack = win.stackId }
                val docked = win.bounds?.let { isClose(it, rect) } == true
                if (docked) attempts = 0
                if (win.mode == "freeform") {
                    // Behind the dashboard (we came back to this page, or the user
                    // touched the dashboard before focus was declined): raise it.
                    if (!win.visible) {
                        Log.i(TAG, "raising $packageName above the dashboard")
                        if (!bringToFront(context, win.taskId)) {
                            lastResult = "failed: could not raise ${win.packageName} (task ${win.taskId})"
                        }
                    }
                    noteFreeform(packageName, true)
                    setDashboardFocusable(context, false)
                } else {
                    noteFreeform(packageName, false)
                }
                status.value = Status(
                    pipPackage = win.packageName, docked = docked, mode = win.mode, seen = lastSeen,
                    windowBounds = win.bounds, target = rect, lastResult = lastResult,
                    gaveUp = attempts >= MAX_ATTEMPTS
                )
                if (!docked && attempts < MAX_ATTEMPTS) {
                    attempts++
                    // Stack commands first; if the system keeps ignoring them,
                    // drag the window the way a finger would.
                    // Only when the system *refused* the commands: a swipe cannot
                    // resize, and its initial touch makes the PiP expand.
                    val useSwipe = attempts >= 3 && win.bounds != null && lastResult?.startsWith("failed") == true
                    val result = runCatching {
                        if (useSwipe) swipeTo(context, win.bounds!!, rect) else resize(context, win, rect)
                    }
                    lastResult = result.fold({ it }, { "failed: ${it.message}" })
                    result.onFailure { publishError(packageName, it) }
                    if (result.isSuccess) undoStatusBarPolicy(context)
                    status.value = status.value.copy(lastResult = lastResult, error = if (result.isSuccess) null else status.value.error)
                }
            }
            delay(POLL_MS)
        }
    }

    @Volatile private var overlayGrantTried = false

    /**
     * Grants "display over other apps" (for the skin's frame over the docked
     * window) through the dock's shell, since a head unit rarely exposes the
     * settings screen. Tried once per process; true when the permission is held.
     */
    suspend fun grantOverlayPermission(context: Context): Boolean {
        if (android.provider.Settings.canDrawOverlays(context)) return true
        if (overlayGrantTried) return false
        overlayGrantTried = true
        val out = runCatching { shell(context, "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow") }
            .getOrElse { "failed: ${it.message}" }
        // The app-op change can take a moment to reach this process.
        repeat(10) {
            if (android.provider.Settings.canDrawOverlays(context)) {
                Log.i(TAG, "overlay permission granted")
                return true
            }
            delay(200)
        }
        Log.w(TAG, "overlay permission not granted: ${out.trim()}")
        return false
    }

    private var statusBarPolicyChecked = false

    /**
     * An earlier build wrote a per-app immersive policy hoping to keep the
     * status bar hidden with a docked window. Android forces the bar whenever a
     * freeform window is visible, so the policy achieved nothing; clear it once.
     */
    private suspend fun undoStatusBarPolicy(context: Context) {
        if (statusBarPolicyChecked) return
        statusBarPolicyChecked = true
        runCatching {
            val current = shell(context, "settings get global policy_control").trim()
            if (current.startsWith("immersive.status=") && current.contains(context.packageName)) {
                shell(context, "settings delete global policy_control")
                Log.i(TAG, "cleared policy_control ('$current')")
            }
        }.onFailure { Log.w(TAG, "could not check status-bar policy", it) }
    }

    // --- Keeping the docked window in front ---------------------------------
    //
    // Touching the dashboard normally brings its window above the floating Maps
    // window, which is why Maps "disappeared" on every touch. A window that
    // declines key focus (FLAG_NOT_FOCUSABLE) still gets touches but is never
    // raised by them, so the dashboard declines focus while Maps is docked and
    // takes it back when the window is gone. Side effect: no on-screen keyboard
    // for the dashboard while Maps is docked on the visible page.

    private var focusDeclined = false

    private suspend fun setDashboardFocusable(context: Context, focusable: Boolean) {
        val activity = context.findActivity() ?: return
        if (focusable != focusDeclined) return
        focusDeclined = !focusable
        withContext(Dispatchers.Main) {
            val flag = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (focusable) activity.window.clearFlags(flag) else activity.window.addFlags(flag)
        }
        Log.i(TAG, if (focusable) "dashboard takes focus again" else "dashboard declines focus while Maps is docked")
    }

    private fun dashboardTaskId(context: Context): Int? = context.findActivity()?.taskId

    /** Raises a task; needs the normal REORDER_TASKS permission and a foreground caller, both true here. */
    private fun bringToFront(context: Context, taskId: Int?): Boolean {
        taskId ?: return false
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION)
            true
        }.onFailure { Log.w(TAG, "moveTaskToFront($taskId) failed", it) }.getOrDefault(false)
    }

    private fun Context.findActivity(): android.app.Activity? {
        var c: Context? = this
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Give up after this many placement attempts per window, so we never fight SystemUI forever. */
    private const val MAX_ATTEMPTS = 6

    /**
     * "Close enough": the window's centre is inside the target and its width is
     * within the band SystemUI's aspect-ratio rules can produce. Exact equality
     * never happens once the system has had its say.
     */
    internal fun isClose(actual: ScreenRect, target: ScreenRect): Boolean {
        val cx = (actual.left + actual.right) / 2
        val cy = (actual.top + actual.bottom) / 2
        val insideX = cx in target.left..target.right
        val insideY = cy in target.top..target.bottom
        val w = (actual.right - actual.left).toFloat()
        val tw = (target.right - target.left).toFloat().coerceAtLeast(1f)
        return insideX && insideY && w / tw in 0.6f..1.4f
    }

    /** Drags the window by its centre onto the target centre (SystemUI handles PiP drags itself). */
    private suspend fun swipeTo(context: Context, from: ScreenRect, to: ScreenRect): String {
        val x1 = (from.left + from.right) / 2; val y1 = (from.top + from.bottom) / 2
        val x2 = (to.left + to.right) / 2; val y2 = (to.top + to.bottom) / 2
        val cmd = "input swipe $x1 $y1 $x2 $y2 600"
        val out = shell(context, cmd)
        if (looksLikeError(out)) error(out.trim().lines().first())
        Log.d(TAG, "swiped window: $cmd")
        return "swipe ($x1,$y1)->($x2,$y2): ok"
    }

    /**
     * The tile left the screen: move the window out of the way, to a small
     * rectangle in the bottom-right corner of the display.
     */
    private const val PREFS = "pip_anchor"
    private const val REOPEN_COOLDOWN_MS = 15_000L
    private val lastReopenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Maps keeps its historical key; other apps get one each. */
    private fun autoOpenKey(packageName: String) =
        if (packageName == MAPS_PACKAGE) "auto_open_maps" else "auto_open_$packageName"

    /**
     * Once the user has opened Maps from the tile, the tile's job is "Maps lives
     * here": whenever it is on screen and no Maps window exists, whatever closed
     * it (a page change, another app, a reboot), it opens one. Removing the
     * tile ends that. Persisted so the Maps page survives a restart.
     */
    fun autoOpen(context: Context, packageName: String = MAPS_PACKAGE): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(autoOpenKey(packageName), false)

    fun setAutoOpen(context: Context, on: Boolean, packageName: String = MAPS_PACKAGE) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(autoOpenKey(packageName), on).apply()
    }

    /** Stops keeping windows open for every app that no longer has a tile. */
    fun releaseAutoOpenExcept(context: Context, keep: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("auto_open_") }.forEach { key ->
            val pkg = if (key == "auto_open_maps") MAPS_PACKAGE else key.removePrefix("auto_open_")
            if (pkg !in keep) editor.remove(key)
        }
        editor.apply()
    }

    /**
     * The tile left the screen. A freeform window cannot be hidden: the window
     * manager keeps part of it on screen, and while any freeform window is
     * visible Android forces the (transparent) status bar over the dashboard.
     * So the window is closed and reopened when the tile is back; Maps keeps
     * guiding from its notification meanwhile. Picture-in-picture, which the
     * system keeps on screen anyway, is parked small in the bottom-right corner.
     */
    /**
     * Another app took the whole screen. This ROM keeps floating windows above
     * fullscreen apps too, so the window would sit over that app: close it, and
     * make sure the tile reopens it when the dashboard is back.
     */
    fun closeForOtherApp(context: Context, packageName: String = MAPS_PACKAGE) {
        scope.launch {
            val win = runCatching { findFloatingWindow(context, packageName) }.getOrNull() ?: return@launch
            if (win.mode != "freeform") return@launch
            setAutoOpen(context, true, packageName)
            noteFreeform(packageName, false)
            val out = runCatching { shell(context, "am stack remove ${win.stackId}") }
                .getOrElse { "failed: ${it.message}" }
            Log.i(TAG, "closed $packageName while another app is in front: ${out.trim()}")
            closeConnection()
        }
    }

    /** Back on screen: let [track] reopen the app at once instead of waiting out the cooldown. */
    fun expectReturn(packageName: String) {
        lastReopenAt.remove(packageName)
    }

    fun hide(context: Context, packageName: String = MAPS_PACKAGE) {
        scope.launch {
            noteFreeform(packageName, false)
            val stack = runCatching { findFloatingWindow(context, packageName) }.getOrNull() ?: return@launch
            if (stack.mode == "freeform") {
                // Close it. Raising the dashboard above the window looked cheaper,
                // but this ROM keeps floating windows drawn on top while reporting
                // them as covered, so the window stayed over the edit handles and
                // over other pages. The tile reopens it when it is back on screen.
                setDashboardFocusable(context, true)
                setAutoOpen(context, true, packageName)
                val out = runCatching { shell(context, "am stack remove ${stack.stackId}") }
                    .getOrElse { "failed: ${it.message}" }
                Log.i(TAG, "closed $packageName while its tile is off screen: ${out.trim()}")
            } else {
                val dm = context.resources.displayMetrics
                val w = dm.widthPixels / 4
                val h = w * 9 / 16
                val margin = (12 * dm.density).roundToInt()
                val rect = ScreenRect(dm.widthPixels - w - margin, dm.heightPixels - h - margin, dm.widthPixels - margin, dm.heightPixels - margin)
                runCatching { resize(context, stack, rect) }.onFailure { Log.w(TAG, "park failed", it) }
            }
            closeConnection()
        }
    }


    /** Summary of the last stack listing, e.g. "fullscreen dash · freeform maps". */
    @Volatile private var lastSeen: String? = null
    private var lastListing: String? = null

    private suspend fun findFloatingWindow(context: Context, packageName: String? = null): FloatingWindow? {
        val listing = shell(context, "am stack list")
        if (listing != lastListing) {
            // Full dump once per change: this is what tells us how the ROM
            // reports its floating windows.
            Log.i(TAG, "am stack list:\n$listing")
            lastListing = listing
        }
        lastSeen = summarizeStacks(listing)
        return parseFloatingWindow(listing, context.packageName, packageName)
    }

    private suspend fun resize(context: Context, win: FloatingWindow, rect: ScreenRect): String {
        // `am stack resize` / `am task resize` read LEFT TOP RIGHT BOTTOM as four
        // separate arguments (the help text's "L,T,R,B" is wrong: a comma-joined
        // value fails with NumberFormatException, confirmed on the head unit).
        val bounds = "${rect.left} ${rect.top} ${rect.right} ${rect.bottom}"
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
                return "${cmd.substringBefore(" $bounds")}: ok"
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

    private fun publishError(packageName: String, e: Throwable) {
        Log.w(TAG, "PiP anchor error", e)
        val msg = when {
            e is java.net.ConnectException || e.message?.contains("Connection refused") == true ->
                "No root (Magisk) and the ADB socket on port ${adbPort()} isn't listening"
            else -> e.message ?: e.javaClass.simpleName
        }
        statusFlow(packageName).let { it.value = it.value.copy(error = msg) }
    }

    /**
     * Picks the floating window out of `am stack list` output. Each stack is a
     * block starting with `Stack id=N`; its configuration names the windowing
     * mode (`mWindowingMode=pinned` / `freeform`) and its tasks appear as
     * `taskId=N: package/activity`. Freeform wins over pinned; our own package
     * and the Home stack are never candidates.
     */
    internal fun parseFloatingWindow(output: String, selfPackage: String = "com.openauto.dash", packageName: String? = null): FloatingWindow? {
        val found = mutableListOf<FloatingWindow>()
        for (block in stackBlocks(output)) {
            val mode = windowingMode(block) ?: continue
            if (mode != "pinned" && mode != "freeform") continue
            if (block.contains("ActivityType=home")) continue
            val id = block.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
            val task = TASK.find(block) ?: continue
            val pkg = task.groupValues[2]
            if (pkg == selfPackage) continue
            if (packageName != null && pkg != packageName) continue
            // The stack's own bounds line comes first and, for freeform, spans
            // the whole display; the window's bounds are on the task line.
            val taskLine = block.substring(task.range.first).lineSequence().first()
            val b = (BOUNDS.find(taskLine) ?: BOUNDS.find(block))?.groupValues
            val bounds = b?.let { ScreenRect(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt()) }
            val visible = !taskLine.contains("visible=false")
            found += FloatingWindow(id, task.groupValues[1].toIntOrNull(), pkg, bounds, mode, visible)
        }
        // A freeform window carries the full Maps UI; prefer it over a PiP.
        return found.firstOrNull { it.mode == "freeform" } ?: found.firstOrNull()
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
internal fun PipAnchorCard(
    modifier: Modifier = Modifier,
    isDock: Boolean = false,
    packageName: String = PipAnchor.MAPS_PACKAGE,
    appLabel: String = "Maps"
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
                Text("MAPS WINDOW", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Text("Maps is docked beside the dashboard.", color = DashColors.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val status by PipAnchor.statusOf(packageName).collectAsState()

    var target by remember { mutableStateOf<PipAnchor.ScreenRect?>(null) }
    var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { started = true; PipAnchor.expectReturn(packageName) }
                // Another app took the whole screen: its task covers Maps, so there
                // is nothing to hide; coming back, track() raises Maps again. Just
                // stop polling meanwhile. (Touching the Maps window only *pauses*
                // the launcher, which must not hide anything either.)
                Lifecycle.Event.ON_STOP -> { started = false; PipAnchor.closeForOtherApp(context, packageName) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            PipAnchor.hide(context, packageName)
        }
    }

    // Re-target after the tile settles: a page swipe or a drag in edit mode
    // moves it many times per second, and each ADB round trip costs real time.
    LaunchedEffect(target, started) {
        val rect = target ?: return@LaunchedEffect
        if (!started) return@LaunchedEffect
        delay(350)
        PipAnchor.track(context, rect, packageName)
    }

    // The skin's frame (a round porthole, a chrome bezel...) over the docked Maps
    // window, only while it actually sits here and the dashboard is on screen.
    WindowFrameOverlay(
        bounds = status.windowBounds.takeIf { isMaps && started && status.docked && status.pipPackage != null }
    )

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
            Text("${appLabel.uppercase()} WINDOW", color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            val pkg = status.pipPackage
            val err = status.error
            val name = pkg?.substringAfterLast('.')
            Text(
                text = when {
                    pkg != null && status.docked -> "Docked: $name (${status.mode})"
                    pkg != null && status.gaveUp -> "The system keeps $name where it is"
                    pkg != null -> "Moving $name here…"
                    PipAnchor.autoOpen(context, packageName) -> "Opening $appLabel here\u2026"
                    isMaps -> "Google Maps docks here.\nOpen it below, or start guidance and press Home."
                    else -> "$appLabel runs here, in a window the size of this tile."
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
                Text("Window $at \u2192 target $to", color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                status.lastResult?.let {
                    Text("Last: $it", color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
            status.seen?.let { seen ->
                Spacer(Modifier.height(6.dp))
                Text("Windows: $seen", color = DashColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
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
                ) { Text(if (pkg == null) "Open $appLabel here" else "Open full $appLabel here") }
            }
        }
    }
}
