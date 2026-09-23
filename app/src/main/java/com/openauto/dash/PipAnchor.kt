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
 * positioned to match; when the tile scrolls away the window is parked
 * aside, still running, so a navigation or a song carries on meanwhile.
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
        val gaveUp: Boolean = false,
        /** The window's size (w, h px) when the system made it clearly larger than the tile (its minimum size). */
        val oversizePx: Pair<Int, Int>? = null,
        /** When a poll last looked (ms): a status not refreshed for a while is stale, e.g. while parked aside. */
        val checkedAt: Long = 0L,
        /** The listing's visibility flag, and whether it puts the dashboard in front of the window. */
        val visible: Boolean? = null,
        val behindDashboard: Boolean? = null
    )

    // One status per docked app: several tiles (Maps, YouTube Music, ...) can
    // each own a window at the same time.
    private val statuses = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Status>>()

    private fun statusFlow(packageName: String) = statuses.getOrPut(packageName) { MutableStateFlow(Status()) }

    fun statusOf(packageName: String): StateFlow<Status> = statusFlow(packageName)

    /** Packages currently docked as freeform windows (drives the status-bar inset). */
    val dockedPackages = MutableStateFlow<Set<String>>(emptySet())

    private val freeformNow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Packages a tile has tracked in this process. Together with the persisted
     * auto-open intent this is what "managed" means: windows of these apps are
     * ours to close when no tile shows them.
     */

    private val managed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * How many tiles currently show each app, counted from composition (a tile
     * enters / leaves the screen), not from the tracking loop: the loop restarts
     * on every re-target and a window must not look ownerless meanwhile.
     */

    private val tileCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun tileShown(packageName: String) {
        tileCounts.merge(packageName, 1, Int::plus)
        managed.add(packageName)
        lastReopenAt.remove(packageName) // back on screen: reopen at once if needed
        lastRaiseAt.remove(packageName) // and raise a window parked behind the dashboard at once
    }

    fun tileHidden(packageName: String) {
        tileCounts.compute(packageName) { _, n -> if (n == null || n <= 1) null else n - 1 }
    }

    private fun activePackages(): Set<String> = tileCounts.keys.toSet()

    private fun managedPackages(context: Context): Set<String> = managed + autoOpenPackages(context)

    /**
     * Apps with a window tile anywhere in the dashboard, on any page (set by the
     * dashboard; null until it has). Their windows outlive a page change: they
     * are parked aside, still running, instead of being closed, so a Maps
     * navigation or a YouTube Music song carries on on the other pages.
     */
    val placedPackages = MutableStateFlow<Set<String>?>(null)

    /** Unknown yet counts as placed: closing a window by mistake loses the app's state. */
    private fun isPlaced(packageName: String): Boolean = placedPackages.value?.contains(packageName) ?: true

    /** Windows parked aside (off-page, or out of a dialog's way): alive, but not on the dashboard. */
    private val parked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Freeform windows actually showing on the dashboard (not parked aside). */
    private fun onScreenWindows(): Set<String> = freeformNow - parked

    /**
     * Safety net for windows whose tile is not on screen: parked aside while
     * their app still has a tile on another page, closed once it has none.
     */
    private suspend fun handleStrays(context: Context, listing: String) {
        for (stray in WindowListing.strayWindows(listing, managedPackages(context), activePackages())) {
            if (isPlaced(stray.packageName)) park(context, stray, "no tile on screen")
            else closeWindow(context, stray, "no tile left")
        }
    }

    /** Forgets windows that are gone from [listing] (e.g. a parked one the system closed). */
    private fun syncFreeform(listing: String) {
        val present = WindowListing.allFloatingWindows(listing).filter { it.mode == "freeform" }.map { it.packageName }.toSet()
        for (pkg in freeformNow.toList()) if (pkg !in present) {
            parked.remove(pkg)
            noteFreeform(pkg, false)
        }
    }

    /** Windows we closed ourselves; their disappearance must not count as "the user closed it". */
    private val expectedGone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The one way a window is closed: removes its stack, forgets its state, and
     * gives the dashboard its focus back when no docked window is left. Every
     * close path goes through here so they cannot drift apart.
     */

    private suspend fun closeWindow(context: Context, win: FloatingWindow, reason: String) {
        expectedGone.add(win.packageName)
        parked.remove(win.packageName)
        noteFreeform(win.packageName, false)
        statusFlow(win.packageName).value = Status(seen = lastSeen)
        val out = runCatching { DockShell.shell(context, "am stack remove ${win.stackId}") }.getOrElse { "failed: ${it.message}" }
        Log.i(TAG, "closed ${win.packageName} ($reason): ${out.trim()}")
        if (onScreenWindows().isEmpty()) setDashboardFocusable(context, true)
    }

    /**
     * The way a window leaves the dashboard without being closed: slid off the
     * right edge at its current size (a thin strip stays visible, the system
     * will not hide a freeform window completely). The app keeps running; the
     * tile brings the window back when it is on screen again.
     */

    private suspend fun park(context: Context, win: FloatingWindow, reason: String) {
        if (win.mode == "freeform") noteFreeform(win.packageName, true)
        parked.add(win.packageName)
        if (onScreenWindows().isEmpty()) setDashboardFocusable(context, true)
        val b = win.bounds ?: return
        val dm = context.resources.displayMetrics
        if (b.left >= dm.widthPixels - ASIDE_SLIVER_PX) return // already aside
        val left = dm.widthPixels - ASIDE_SLIVER_PX
        runGuarded { DockShell.resize(context, win, ScreenRect(left, b.top, left + (b.right - b.left), b.bottom)) }
            .onFailure { Log.w(TAG, "park aside failed", it) }
        Log.i(TAG, "${win.packageName} parked aside ($reason)")
    }

    @Synchronized

    private fun noteFreeform(packageName: String, present: Boolean) {
        if (present) freeformNow.add(packageName) else freeformNow.remove(packageName)
        dockedPackages.value = freeformNow.toSet()
    }

    /**
     * Screen areas of our own pop-ups (menus, dialogs) on screen right now, by
     * owner. Docked windows are drawn above them, so a window whose tile
     * overlaps one steps aside meanwhile; windows elsewhere stay open.
     */
    val coveredAreas = MutableStateFlow<Map<Any, ScreenRect>>(emptyMap())

    /** runCatching that never swallows coroutine cancellation. */
    private inline fun <T> runGuarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** True while the "Maps left" layout's permanent dock is on screen. */
    val dockActive = MutableStateFlow(false)

    /**
     * The dashboard's content area in screen pixels (pages, without the bar).
     * Windows are kept inside it: the ROM enforces a minimum size per app, so a
     * window can come out taller than its tile, and it must then be moved up
     * rather than be allowed to cover the launcher bar.
     */

    val allowedArea = MutableStateFlow<ScreenRect?>(null)

    /** Every docked window steps aside (the app drawer is open). */
    val steppedAside = MutableStateFlow(false)

    /** The pages are being swiped: windows on a page step aside with it; a dock beside the pages stays. */
    val pageSwiping = MutableStateFlow(false)

    /** Park requests in flight, one per app: a tile asking again meanwhile has nothing to add. */
    private val parking = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Slides [packageName]'s window off the right edge at its current size (a
     * thin strip stays visible). One park at a time per app: a second request
     * while one is under way would only repeat its round trips.
     */
    fun parkAside(context: Context, packageName: String = MAPS_PACKAGE) {
        parking.compute(packageName) { _, running ->
            if (running?.isActive == true) running
            else scope.launch {
                val win = runCatching { findFloatingWindow(context, packageName) }.getOrNull() ?: return@launch
                park(context, win, "stepped aside for a dialog")
            }
        }
    }

    private const val ASIDE_SLIVER_PX = 4

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun track(context: Context, rect: ScreenRect, packageName: String = MAPS_PACKAGE) {
        val status = statusFlow(packageName)
        managed.add(packageName)
        var mem = DockPolicy.Memory()
        var lastResult: String? = null
        while (true) {
            val lookup = runGuarded { findFloatingWindow(context, packageName) }
            // Safety net: a window whose tile left the screen but which a missed
            // hide() left in place is parked here (closed if its tile is gone).
            lastListing?.let { runGuarded { handleStrays(context, it) } }
            val win = lookup.getOrNull()
            val now = System.currentTimeMillis()
            if (lookup.isFailure) {
                publishError(context, packageName, lookup.exceptionOrNull()!!)
                mem = mem.copy(attempts = 0, lastStack = null)
            } else if (win == null) {
                status.value = status.value.copy(pipPackage = null, docked = false, mode = null, seen = lastSeen, windowBounds = null, oversizePx = null, checkedAt = now, visible = null, behindDashboard = null)
                parked.remove(packageName)
                noteFreeform(packageName, false)
                if (onScreenWindows().isEmpty()) setDashboardFocusable(context, true)
                val (step, next) = DockPolicy.onMissing(
                    mem, expectedGone = expectedGone.remove(packageName),
                    autoOpen = autoOpen(context, packageName),
                    lastReopenAt = lastReopenAt[packageName] ?: 0L, now = now
                )
                mem = next
                when (step) {
                    DockPolicy.Step.UserClosed -> {
                        // Gone, and not by our hand: the user closed it. Respect that.
                        Log.i(TAG, "$packageName closed by the user; not reopening")
                        setAutoOpen(context, false, packageName)
                    }
                    DockPolicy.Step.GiveUp -> {
                        // Launched twice and no window ever showed up: the app opened
                        // fullscreen or refused. Stop, or Home would be trapped.
                        Log.w(TAG, "$packageName never appeared as a window; giving up")
                        setAutoOpen(context, false, packageName)
                        status.value = status.value.copy(error = context.getString(R.string.apps_window_error_gave_up))
                    }
                    is DockPolicy.Step.Reopen -> {
                        lastReopenAt[packageName] = now
                        val bounds = android.graphics.Rect(rect.left, rect.top, rect.right, rect.bottom)
                        Log.i(TAG, "opening $packageName at $rect (attempt ${step.attempt})")
                        SplitLauncher.launchFreeform(context, packageName, bounds)
                    }
                    else -> Unit
                }
            } else {
                expectedGone.remove(packageName)
                // Back from being parked (another page, a dialog): placed below.
                parked.remove(packageName)
                val (step, next) = DockPolicy.onPresent(
                    mem, win, rect, allowedArea.value,
                    lastRaiseAt = lastRaiseAt[packageName] ?: 0L, now = now
                )
                mem = next
                val keep = step as DockPolicy.Step.Keep
                if (win.mode == "freeform") {
                    noteFreeform(packageName, true)
                    setDashboardFocusable(context, false)
                } else {
                    noteFreeform(packageName, false)
                }
                status.value = Status(
                    pipPackage = win.packageName, docked = keep.docked, mode = win.mode, seen = lastSeen,
                    windowBounds = win.bounds, target = rect, lastResult = lastResult,
                    gaveUp = keep.gaveUp, oversizePx = keep.oversizePx,
                    checkedAt = now, visible = win.visible, behindDashboard = win.behindDashboard
                )
                val place = keep.place
                var placementFailed = false
                if (place != null) {
                    val result = runGuarded {
                        if (keep.swipe) DockShell.swipeTo(context, win.bounds!!, place) else DockShell.resize(context, win, place)
                    }
                    lastResult = result.fold({ it }, { "failed: ${it.message}" })
                    placementFailed = result.isFailure
                    mem = mem.copy(lastPlacementFailed = result.isFailure)
                    result.onFailure { publishError(context, packageName, it) }
                    if (result.isSuccess) undoStatusBarPolicy(context)
                }
                if (keep.raise) {
                    // Arriving on its tile (this page came back, the window was
                    // parked aside), just settled there, or behind the dashboard:
                    // raise it. After placing it, so the window raised is one on
                    // the tile, not a sliver at the edge of the screen.
                    lastRaiseAt[packageName] = now
                    Log.i(TAG, "raising $packageName above the dashboard")
                    if (bringToFront(context, win.taskId)) DockShell.forgetListing()
                    else lastResult = listOfNotNull(lastResult.takeIf { place != null }, "failed: could not raise ${win.packageName} (task ${win.taskId})").joinToString(" \u00b7 ")
                }
                if (place != null || keep.raise) {
                    status.value = status.value.copy(lastResult = lastResult, error = if (placementFailed) status.value.error else null)
                }
                if (keep.raise) {
                    // Arriving on its tile (this page came back, the window was
                    // parked aside), or behind the dashboard: bring it in front,
                    // once it is in place. Starting its task again is what shows
                    // it on this head unit; moving the task to the front alone
                    // could leave it invisible there.
                    lastRaiseAt[packageName] = now
                    Log.i(TAG, "raising $packageName above the dashboard")
                    val relaunched = runGuarded { DockShell.relaunch(context, win) }
                        .onFailure { Log.w(TAG, "relaunching $packageName failed", it) }
                    if (relaunched.isSuccess || bringToFront(context, win.taskId)) {
                        DockShell.forgetListing()
                        relaunched.getOrNull()?.let { lastResult = it }
                    } else {
                        lastResult = "failed: could not raise ${win.packageName} (task ${win.taskId})"
                    }
                    status.value = status.value.copy(lastResult = lastResult)
                }
            }
            delay(POLL_MS)
        }
    }


    private val lastRaiseAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The user tapped a tile its window should be covering: the window is
     * behind the dashboard, whatever the listing says. Raise it right away.
     */
    fun raiseNow(context: Context, packageName: String = MAPS_PACKAGE) {
        scope.launch {
            val win = runCatching { findFloatingWindow(context, packageName) }.getOrNull() ?: return@launch
            if (win.mode != "freeform") return@launch
            lastRaiseAt[packageName] = System.currentTimeMillis()
            Log.i(TAG, "raising $packageName above the dashboard (tapped its tile)")
            if (bringToFront(context, win.taskId)) DockShell.forgetListing()
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
        val out = runCatching { DockShell.shell(context, "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow") }
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
            val current = DockShell.shell(context, "settings get global policy_control").trim()
            if (current.startsWith("immersive.status=") && current.contains(context.packageName)) {
                DockShell.shell(context, "settings delete global policy_control")
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

    @Volatile private var focusDeclined = false

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


    /**
     * "Close enough": the window's centre is inside the target and its width is
     * within the band SystemUI's aspect-ratio rules can produce. Exact equality
     * never happens once the system has had its say.
     */

    private const val PREFS = "pip_anchor"


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

    /** Every app with a persisted keep-open intent. */
    private fun autoOpenPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .filter { (k, v) -> k.startsWith("auto_open_") && v == true }
            .keys.map { if (it == "auto_open_maps") MAPS_PACKAGE else it.removePrefix("auto_open_") }.toSet()

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
     * Another app took the whole screen. This ROM keeps floating windows above
     * fullscreen apps too, so the window would sit over that app: park it
     * aside, still running (closing it stopped the music and reset Maps'
     * guidance); the tile docks it again when the dashboard is back.
     */

    fun parkForOtherApp(context: Context, packageName: String = MAPS_PACKAGE) {
        scope.launch {
            val win = runCatching { findFloatingWindow(context, packageName) }.getOrNull() ?: return@launch
            if (win.mode == "freeform") park(context, win, "another app is in front")
            DockShell.release()
        }
    }

    /**
     * Clears the dashboard of every managed window whose app is not in [onPage].
     * Called the moment the pager's current page changes, so a window leaves
     * with the swipe instead of a few seconds later when the old page is
     * finally disposed. Windows whose app still has a tile on another page are
     * parked aside, still running; only those whose tile is gone are closed.
     */

    fun stashAllExcept(context: Context, onPage: Set<String>) {
        scope.launch {
            val listing = runCatching { DockShell.listStacks(context) }.getOrNull() ?: return@launch
            syncFreeform(listing)
            val mine = managedPackages(context)
            for (win in WindowListing.allFloatingWindows(listing, context.packageName)) {
                if (win.mode != "freeform" || win.packageName !in mine || win.packageName in onPage) continue
                if (isPlaced(win.packageName)) park(context, win, "page change")
                else closeWindow(context, win, "tile removed")
            }
        }
    }

    /** Back on screen: let [track] reopen the app at once instead of waiting out the cooldown. */
    fun expectReturn(packageName: String) {
        lastReopenAt.remove(packageName)
    }

    /**
     * The tile left the screen (another page, edit mode). A freeform window
     * cannot be hidden: the window manager keeps part of it on screen. Raising
     * the dashboard above it does not help either, this ROM keeps floating
     * windows drawn on top. So it is parked aside, still running (closing it
     * reset Maps' guidance and stopped the music), and the tile docks it again
     * when it is back; it is closed only once its app has no tile left.
     * Picture-in-picture, which the system keeps on screen anyway, is parked
     * small in the bottom-right corner. Nothing is done while another tile of
     * the same app is on screen (the app on both pages of a swipe): the window
     * is that tile's now, and must not be parked from under it.
     */
    fun hide(context: Context, packageName: String = MAPS_PACKAGE) {
        if ((tileCounts[packageName] ?: 0) > 0) return
        scope.launch {
            val stack = runCatching { findFloatingWindow(context, packageName) }.getOrNull()
            if (stack == null) {
                // Already gone: still hand focus back.
                parked.remove(packageName)
                noteFreeform(packageName, false)
                if (onScreenWindows().isEmpty()) setDashboardFocusable(context, true)
                return@launch
            }
            if (stack.mode == "freeform") {
                if (isPlaced(packageName)) park(context, stack, "tile off screen")
                else closeWindow(context, stack, "tile removed")
            } else {
                val dm = context.resources.displayMetrics
                val w = dm.widthPixels / 4
                val h = w * 9 / 16
                val margin = (12 * dm.density).roundToInt()
                val rect = ScreenRect(dm.widthPixels - w - margin, dm.heightPixels - h - margin, dm.widthPixels - margin, dm.heightPixels - margin)
                runCatching { DockShell.resize(context, stack, rect) }.onFailure { Log.w(TAG, "park failed", it) }
            }
        }
    }

    /** Summary of the last stack listing, e.g. "fullscreen dash · freeform maps". */
    @Volatile private var lastSeen: String? = null

    private var lastListing: String? = null

    private suspend fun findFloatingWindow(context: Context, packageName: String? = null): FloatingWindow? {
        val listing = DockShell.listStacks(context)
        if (listing != lastListing) {
            // Full dump once per change: this is what tells us how the ROM
            // reports its floating windows.
            Log.i(TAG, "am stack list:\n$listing")
            lastListing = listing
        }
        lastSeen = WindowListing.summarizeStacks(listing)
        return WindowListing.parseFloatingWindow(listing, context.packageName, packageName)
    }

    /** Shows [e] on the tile; [context] resolves the message in the UI language (shell errors stay as the system wrote them). */
    private fun publishError(context: Context, packageName: String, e: Throwable) {
        Log.w(TAG, "PiP anchor error", e)
        val msg = when {
            e is java.net.ConnectException || e.message?.contains("Connection refused") == true ->
                context.getString(R.string.apps_window_error_no_shell, DockShell.adbPort())
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
}
