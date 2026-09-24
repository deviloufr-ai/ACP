package com.openauto.dash

import android.content.Context
import android.util.Log
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The privileged shell the docking system talks to the window manager with:
 * Magisk root when granted, else the head unit's internal ADB socket. One
 * command at a time, bounded so a stuck prompt or a hung adbd cannot freeze the
 * dashboard. Also the two window-manager commands built on it (resize, swipe).
 */
object DockShell {

    private const val TAG = "DockShell"

    private val io = Mutex()

    private var dadb: Dadb? = null

    /**
     * Keeps the PiP window on [rect] (screen pixels) until cancelled: finds the
     * pinned stack every few seconds and resizes it whenever it drifted.
     */

    /** Drags the window by its centre onto the target centre (SystemUI handles PiP drags itself). */
    suspend fun swipeTo(context: Context, from: ScreenRect, to: ScreenRect): String {
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

    suspend fun resize(context: Context, win: FloatingWindow, rect: ScreenRect): String =
        resizeWith(context, win, rect, resizeCommands(win, rect))

    /** Tries [attempts] in order and remembers which form the unit accepted. */
    private suspend fun resizeWith(context: Context, win: FloatingWindow, rect: ScreenRect, attempts: List<String>): String {
        var last = ""
        for (cmd in attempts) {
            last = shell(context, cmd)
            if (!looksLikeError(last)) return resized(win, rect, cmd)
            Log.w(TAG, "`$cmd` failed: ${last.trim()}")
            if (cmd.startsWith("am task resize")) taskResizeRefused = true
        }
        error(last.trim().lines().firstOrNull().orEmpty().ifBlank { "resize refused" })
    }

    private fun resized(win: FloatingWindow, rect: ScreenRect, cmd: String): String {
        val bounds = bounds(rect)
        Log.d(TAG, "${win.mode} ${win.packageName} -> $bounds via `$cmd`")
        return "${cmd.substringBefore(" $bounds")}: ok"
    }

    /**
     * This head unit refuses `am task resize` ("resizeTask not allowed") and
     * only takes `am stack resize`. Once seen, the refused form is not sent
     * again: every placement would otherwise cost a round trip for nothing.
     */
    @Volatile private var taskResizeRefused = false

    // `am stack resize` / `am task resize` read LEFT TOP RIGHT BOTTOM as four
    // separate arguments (the help text's "L,T,R,B" is wrong: a comma-joined
    // value fails with NumberFormatException, confirmed on the head unit).
    private fun bounds(rect: ScreenRect) = "${rect.left} ${rect.top} ${rect.right} ${rect.bottom}"

    /** The resize commands worth trying for [win], best first. */
    private fun resizeCommands(win: FloatingWindow, rect: ScreenRect): List<String> {
        val bounds = bounds(rect)
        return if (win.mode == "pinned") {
            // Android 10/11 accept both; the animated form is nicer when present.
            listOf("am stack resize-animated ${win.stackId} $bounds", "am stack resize ${win.stackId} $bounds")
        } else {
            listOfNotNull(
                win.taskId?.takeUnless { taskResizeRefused }?.let { "am task resize $it $bounds" },
                "am stack resize ${win.stackId} $bounds"
            )
        }
    }

    /**
     * Brings [win]'s own task in front by starting it again, the way the
     * launcher does, but into that exact task (`--task`: an app can also have a
     * fullscreen task of its own). The running activity is brought forward, not
     * restarted, so Maps keeps guiding. On the head unit a window moved back
     * onto its tile could stay invisible, while a started one always shows.
     */
    suspend fun relaunch(context: Context, win: FloatingWindow): String {
        val out = shell(context, startCommand(context, win))
        return started(win, out)
    }

    private fun startCommand(context: Context, win: FloatingWindow): String {
        val taskId = win.taskId ?: error("no task id")
        val component = context.packageManager.getLaunchIntentForPackage(win.packageName)?.component
            ?: error("${win.packageName} has no launcher activity")
        return "am start --task $taskId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-f 0x10000000 -p ${win.packageName} -n ${component.flattenToShortString()}"
    }

    private fun started(win: FloatingWindow, out: String): String {
        if (looksLikeError(out)) error(out.trim().lines().lastOrNull().orEmpty().ifBlank { "start refused" })
        Log.d(TAG, "relaunched ${win.packageName} into task ${win.taskId}")
        return "am start --task ${win.taskId}: ok"
    }

    /** Separates the two commands' output in [placeAndRaise]'s single round trip. */
    private const val SPLIT_MARK = "--openauto-dash-split--"

    /**
     * [resize] then [relaunch], in one round trip instead of two: a window
     * coming back to its tile is moved into place and then brought in front.
     * Returns both outcomes. If the resize form was refused, the window was
     * still raised; the other forms are then tried on their own.
     */
    suspend fun placeAndRaise(context: Context, win: FloatingWindow, rect: ScreenRect): Pair<Result<String>, Result<String>> {
        val start = try {
            startCommand(context, win)
        } catch (e: IllegalStateException) {
            return guarded { resize(context, win, rect) } to Result.failure(e)
        }
        val attempts = resizeCommands(win, rect)
        val first = attempts.first()
        // Each command's errors joined to its output, so they stay on their side
        // of the mark; `true` so a refused start is read from its text, like
        // every other command here, not turned into a failed round trip.
        val out = shell(context, "$first 2>&1; echo $SPLIT_MARK; $start 2>&1; true")
        if (SPLIT_MARK !in out) error(out.trim().lines().firstOrNull().orEmpty().ifBlank { "no answer" })
        val resizeOut = out.substringBefore(SPLIT_MARK)
        val raised = runCatching { started(win, out.substringAfter(SPLIT_MARK)) }
        val placed = if (!looksLikeError(resizeOut)) {
            Result.success(resized(win, rect, first))
        } else {
            Log.w(TAG, "`$first` failed: ${resizeOut.trim()}")
            if (first.startsWith("am task resize")) taskResizeRefused = true
            guarded { resizeWith(context, win, rect, attempts.drop(1)) }
        }
        return placed to raised
    }

    /** runCatching that never swallows coroutine cancellation. */
    private inline fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun looksLikeError(out: String): Boolean =
        out.contains("Error", ignoreCase = true) || out.contains("Exception") || out.contains("Unknown")

    /** How shell commands reach the system: root via Magisk, or the ADB socket. */
    private enum class Backend { SU, ADB }

    private var backend: Backend? = null

    suspend fun shell(context: Context, cmd: String): String = withContext(Dispatchers.IO) {
        io.withLock {
            // Any other command may move, raise or close a window.
            listing = null
            execute(context, cmd)
        }
    }

    /** How long an `am stack list` result stands in for a fresh one when nothing was moved meanwhile. */
    private const val LISTING_FRESH_MS = 500L

    /** Only from inside [io]'s lock. */
    private var listing: String? = null
    private var listingAt = 0L

    /**
     * `am stack list`. During a page swipe every tile, the pager and the
     * disposed page each ask for the listing within a few hundred milliseconds;
     * one round trip serves them all, as long as no command in between could
     * have changed what the system would answer.
     */
    suspend fun listStacks(context: Context): String = withContext(Dispatchers.IO) {
        io.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            listing?.takeIf { now - listingAt <= LISTING_FRESH_MS }
                ?: execute(context, "am stack list").also { listing = it; listingAt = now }
        }
    }

    /** The windows changed behind the shell's back (a task was raised): the next listing must be fresh. */
    suspend fun forgetListing() = io.withLock { listing = null }

    /** Only from inside [io]'s lock. */
    private fun execute(context: Context, cmd: String): String {
        val chosen = backend ?: (if (SystemInstaller.isRootAvailable()) Backend.SU else Backend.ADB)
            .also { backend = it; Log.i(TAG, "shell backend: $it") }
        return when (chosen) {
            Backend.SU -> suShell(cmd)
            Backend.ADB -> adbShell(context, cmd)
        }
    }

    /** `su -c cmd`, bounded so a stuck root prompt can't pin the poller. */
    private fun suShell(cmd: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        process.outputStream.close()
        // One builder per stream: the two readers run concurrently.
        val out = StringBuilder()
        val err = StringBuilder()
        val reader = Thread { out.append(process.inputStream.bufferedReader().readText()) }
        val errReader = Thread { err.append(process.errorStream.bufferedReader().readText()) }
        reader.start(); errReader.start()
        if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy()
            throw IllegalStateException("su timed out")
        }
        reader.join(2000); errReader.join(2000)
        val exit = process.exitValue()
        if (exit != 0) {
            // "Permission denied", "not found"...: a failure, whatever it printed.
            val why = (err.toString().ifBlank { out.toString() }).trim().lines().firstOrNull().orEmpty()
            throw IllegalStateException("su exit $exit: $why".trim())
        }
        return out.toString() + err.toString()
    }

    private fun adbShell(context: Context, cmd: String): String {
        val conn = dadb ?: AdbInstaller.connect(context, adbPort(), ADB_TIMEOUT_MS).also { dadb = it }
        try {
            val res = conn.shell(cmd)
            return res.output + res.errorOutput
        } catch (e: Exception) {
            closeConnection()
            throw e
        }
    }

    /** The unit's ADB TCP port from `service.adb.tcp.port`, else the K706 default. */
    fun adbPort(): Int = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
        p.inputStream.bufferedReader().readText().trim().toIntOrNull()
    }.getOrNull() ?: AdbInstaller.DEFAULT_PORT

    /** A hung adbd must not hold the shell lock forever. */
    private const val ADB_TIMEOUT_MS = 5_000

    /** Only from inside [shell]'s lock. */
    private fun closeConnection() {
        runCatching { dadb?.close() }
        dadb = null
    }

    /** Drops the cached ADB connection (harmless with root). Safe from any coroutine. */
    suspend fun release() = io.withLock { closeConnection() }
}
