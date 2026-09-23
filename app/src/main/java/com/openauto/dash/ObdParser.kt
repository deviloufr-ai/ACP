package com.openauto.dash

import java.util.Locale

/**
 * Pure decoding of ELM327 / OBD-II replies, kept free of Android types so it
 * runs under plain JVM unit tests. [ObdBluetoothManager] owns the socket and
 * delegates every reply here.
 */
object ObdParser {

    // --- PID response parsing -------------------------------------------------
    // Responses look like "41 0D 32" for the query "010D". The mode byte is
    // 0x40 + request mode (0x41), followed by the PID and its data bytes.

    internal fun parseSpeed(response: String): Int? {
        val bytes = dataBytes(response, "410D") ?: return null
        return bytes.firstOrNull()
    }

    internal fun parseRpm(response: String): Int? {
        val bytes = dataBytes(response, "410C") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4
    }

    internal fun parseCoolant(response: String): Int? {
        val bytes = dataBytes(response, "4105") ?: return null
        val a = bytes.firstOrNull() ?: return null
        return a - 40
    }

    /** Temperature PIDs: value = A - 40 (°C). */
    internal fun tempFrom(response: String, header: String): Int? {
        val a = dataBytes(response, header)?.firstOrNull() ?: return null
        return a - 40
    }

    /** Percentage PIDs: value = A * 100 / 255. */
    internal fun percentFrom(response: String, header: String): Int? {
        val a = dataBytes(response, header)?.firstOrNull() ?: return null
        return (a * 100) / 255
    }

    /** Parses the ELM327 `ATRV` reply, e.g. "12.3V". */
    internal fun parseVoltage(response: String): Double? =
        Regex("([0-9]+\\.?[0-9]*)").find(response)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    /** Control-module voltage (PID 0142): value = ((A*256)+B) / 1000 volts. */
    internal fun parseControlModuleVoltage(response: String): Double? {
        val bytes = dataBytes(response, "4142") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 1000.0
    }

    /**
     * Decodes a mode-03 reply into DTC strings like "P0133".
     *
     * Each ECU answers with its own message, and the layout depends on the bus:
     *  - Older protocols: one line per 3 codes, always `43` + 6 bytes (7, odd),
     *    zero-padded: `43 01 33 00 00 00 00`.
     *  - CAN (the C4 Picasso): `43`, a code count, then the codes, so always an
     *    even byte count: `43 01 01 33`. More than 2 codes arrive multi-frame as
     *    a byte-count line and numbered lines: `00A` / `0: 43 04 ..` / `1: ..`.
     * Reading a CAN reply the old way turns the count byte into a bogus code.
     */
    internal fun parseDtcs(response: String): List<String> {
        val codes = mutableListOf<String>()
        dtcMessages(response).forEach { bytes ->
            if (bytes.size < 2 || bytes[0] != 0x43) return@forEach
            val can = bytes.size % 2 == 0
            val payload = if (can) bytes.drop(2).take(bytes[1] * 2) else bytes.drop(1)
            payload.chunked(2).forEach { pair ->
                if (pair.size == 2 && (pair[0] != 0 || pair[1] != 0)) codes.add(decodeDtc(pair[0], pair[1]))
            }
        }
        return codes.distinct()
    }

    /** Splits a reply into per-message byte lists, joining CAN multi-frame parts and dropping status lines. */
    private fun dtcMessages(response: String): List<List<Int>> {
        val messages = mutableListOf<MutableList<Int>>()
        // Bytes still expected by the multi-frame message being assembled.
        var remaining = 0
        response.uppercase().split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val frame = Regex("^([0-9A-F]):\\s*(.*)$").find(line)
            val body = (frame?.groupValues?.get(2) ?: line).replace(" ", "")
            // "SEARCHING...", "NO DATA" and the like carry no bytes.
            if (body.isEmpty() || !body.all { it in '0'..'9' || it in 'A'..'F' }) return@forEach
            when {
                frame == null && body.length == 3 -> {
                    remaining = body.toInt(16)
                    messages.add(mutableListOf())
                }
                frame != null && remaining > 0 -> {
                    val bytes = body.chunked(2).mapNotNull { it.toIntOrNull(16) }.take(remaining)
                    messages.last().addAll(bytes)
                    remaining -= bytes.size
                }
                body.length % 2 == 0 -> {
                    remaining = 0
                    messages.add(body.chunked(2).map { it.toInt(16) }.toMutableList())
                }
            }
        }
        return messages
    }

    internal fun decodeDtc(a: Int, b: Int): String {
        val letter = charArrayOf('P', 'C', 'B', 'U')[(a and 0xC0) shr 6]
        val d1 = (a and 0x30) shr 4
        val d2 = a and 0x0F
        val d3 = (b and 0xF0) shr 4
        val d4 = b and 0x0F
        return "%c%d%X%X%X".format(Locale.US, letter, d1, d2, d3, d4)
    }

    /** Extracts the data bytes that follow [header] (e.g. "410D") in [response]. */
    internal fun dataBytes(response: String, header: String): List<Int>? {
        val hex = response
            .uppercase()
            .replace(Regex("[^0-9A-F]"), "")
        val index = hex.indexOf(header)
        if (index < 0) return null

        val payload = hex.substring(index + header.length)
        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 2 <= payload.length) {
            val value = payload.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(value)
            i += 2
        }
        return if (bytes.isEmpty()) null else bytes
    }

}
