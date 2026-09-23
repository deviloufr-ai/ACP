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

    suspend fun resize(context: Context, win: FloatingWindow, rect: ScreenRect): String {
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

    suspend fun shell(context: Context, cmd: String): String = withContext(Dispatchers.IO) {
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
