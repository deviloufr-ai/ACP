package com.openauto.dash

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the head unit shows while the learning screen waits for a button:
 * Android keys, CAN frames that change after being still, raw input events
 * (root `getevent`) and log lines about keys (root `logcat`). Firmware differs
 * in where the wheel buttons surface; this shows where they do on this one,
 * and a key or CAN line can be tapped to learn it ([Line.learn]).
 */
internal object WheelMonitor {
    enum class Source { KEY, CAN, INPUT, LOG }

    data class Line(val id: Long, val source: Source, val text: String, val learn: WheelKey?)

    private const val MAX_LINES = 14
    private val LOG_MATCH = Regex("""(?i)\b(key|keycode|swc|wheel|steer|button)""")
    // The CAN stream already has its own lines; McuReader parses them.
    private val LOG_SKIP = Regex("dispatchToClients")

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    /** Newest first. */
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = mutableListOf<Process>()
    private val jobs = mutableListOf<Job>()
    private var nextId = 0L

    fun add(source: Source, text: String, learn: WheelKey? = null) {
        _lines.update { listOf(Line(nextLineId(), source, text.take(160), learn)) + it.take(MAX_LINES - 1) }
    }

    @Synchronized
    private fun nextLineId(): Long = nextId++

    @Synchronized
    fun start() {
        stopLocked()
        _lines.value = emptyList()
        jobs += sniff("exec getevent -lq", Source.INPUT) { line ->
            line.takeIf { "EV_KEY" in it }?.let { tidyGetevent(it) }
        }
        jobs += sniff("exec logcat -v brief -T 1", Source.LOG) { line ->
            line.takeIf { LOG_MATCH.containsMatchIn(it) && !LOG_SKIP.containsMatchIn(it) }
        }
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        processes.forEach(::kill)
        processes.clear()
    }

    /** "/dev/input/event2: EV_KEY KEY_NEXTSONG DOWN" → "KEY_NEXTSONG DOWN (event2)". */
    internal fun tidyGetevent(line: String): String {
        val device = line.substringBefore(':').substringAfterLast('/')
        val rest = line.substringAfter("EV_KEY").trim().split(Regex("\\s+")).joinToString(" ")
        return if (device.isNotEmpty() && device != line) "$rest ($device)" else rest
    }

    /** Runs [command] under su for as long as the screen listens; [pick] keeps a line and says how to show it. */
    private fun sniff(command: String, source: Source, pick: (String) -> String?): Job = scope.launch {
        val p = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", command)) }.getOrNull() ?: return@launch
        synchronized(this@WheelMonitor) { processes += p }
        try {
            val reader = p.inputStream.bufferedReader()
            while (isActive) {
                val line = reader.readLine() ?: break
                pick(line)?.let { add(source, it.trim()) }
            }
        } catch (e: IOException) {
            // Killed by stop(), or su refused: nothing more to show from here.
        } finally {
            kill(p)
        }
    }

    private fun kill(p: Process) {
        runCatching { p.inputStream.close() }
        runCatching { p.destroy() }
        runCatching { p.destroyForcibly() }
    }
}
