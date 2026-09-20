package com.openauto.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads the head unit's **CANbox/MCU** stream by tailing the `mcu_services`
 * logcat (needs root). The MCU logs every car value as
 * `dispatchToClients - cmdId: X - data : [ .. ]`, so we parse those into a live
 * map of `key -> latest bytes`, tracking when each last changed (for the CAN
 * monitor's "flip" highlight and, later, to read door/fuel/light state).
 *
 * cmdId 65 (control 0x41) is a multiplexed status channel `41 fd <sub> ..`, so
 * we split those into per-`sub` keys ("65.0C", "65.05", …) to keep each value
 * stable instead of overwriting each other.
 */
object McuReader {

    data class Entry(val key: String, val cmdId: Int, val bytes: List<Int>, val hex: String, val changedAt: Long)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * The door bitfield byte from the MCU status frame `41 FD 0C 38 <bits> …`
     * (cmdId 65, sub 0x0C, subtype 0x38). Confirmed on the C4 Picasso: bit 0x80
     * = front-left door. Other bits map to the other doors (to be confirmed).
     * null until first seen.
     */
    private val _doorBits = MutableStateFlow<Int?>(null)
    val doorBits: StateFlow<Int?> = _doorBits.asStateFlow()

    /** Decoded door open/closed state (C4 Picasso bit map, confirmed 2026-09-20). */
    data class DoorState(
        val frontLeft: Boolean = false,
        val frontRight: Boolean = false,
        val rearLeft: Boolean = false,
        val rearRight: Boolean = false,
        val tailgate: Boolean = false,
        val bonnet: Boolean = false
    ) {
        val anyOpen: Boolean get() = frontLeft || frontRight || rearLeft || rearRight || tailgate || bonnet
    }

    private val _doorState = MutableStateFlow<DoorState?>(null)
    val doorState: StateFlow<DoorState?> = _doorState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var process: Process? = null
    private var refCount = 0
    private val latest = LinkedHashMap<String, Entry>()

    private val regex = Regex("""dispatchToClients - cmdId:\s*(\d+)\s*-\s*data\s*:\s*\[([0-9a-fA-F ]*)]""")

    @Synchronized
    fun start() {
        refCount++
        if (job != null) return
        job = scope.launch {
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -s mcu_services:D"))
                process = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (isActive) parse(line)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        refCount--
        if (refCount > 0) return
        refCount = 0
        runCatching { process?.destroy() }
        process = null
        job?.cancel()
        job = null
    }

    private fun parse(line: String) {
        val m = regex.find(line) ?: return
        val cmdId = m.groupValues[1].toIntOrNull() ?: return
        val bytes = m.groupValues[2].trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull(16) }
        if (bytes.isEmpty()) return

        val sub = if (bytes.size >= 3 && bytes[1] == 0xfd) bytes[2] else -1
        val key = if (sub >= 0) "%d.%02X".format(cmdId, sub) else cmdId.toString()
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        val now = System.currentTimeMillis()
        val prev = latest[key]
        val changedAt = if (prev == null || prev.hex != hex) now else prev.changedAt
        latest[key] = Entry(key, cmdId, bytes, hex, changedAt)
        _entries.value = latest.values.sortedBy { it.key }

        // Door bitfield: cmdId 65, [.. 0C 38 <bits> ..] → byte index 4.
        if (cmdId == 65 && bytes.size > 4 && bytes[2] == 0x0C && bytes[3] == 0x38) {
            val b = bytes[4]
            _doorBits.value = b
            _doorState.value = DoorState(
                frontLeft = b and 0x80 != 0,
                frontRight = b and 0x40 != 0,
                rearLeft = b and 0x20 != 0,
                rearRight = b and 0x10 != 0,
                tailgate = b and 0x08 != 0,
                bonnet = b and 0x04 != 0
            )
        }
    }
}
