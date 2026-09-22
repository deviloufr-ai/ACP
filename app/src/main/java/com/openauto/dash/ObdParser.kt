package com.openauto.dash

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

    /** Decodes a mode-03 reply into DTC strings like "P0133". */
    internal fun parseDtcs(response: String): List<String> {
        val hex = response.uppercase().replace(Regex("[^0-9A-F]"), "")
        val index = hex.indexOf("43")
        if (index < 0) return emptyList()
        val payload = hex.substring(index + 2)
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= payload.length) {
            val a = payload.substring(i, i + 2).toIntOrNull(16) ?: break
            val b = payload.substring(i + 2, i + 4).toIntOrNull(16) ?: break
            i += 4
            if (a == 0 && b == 0) continue
            codes.add(decodeDtc(a, b))
        }
        return codes.distinct()
    }

    internal fun decodeDtc(a: Int, b: Int): String {
        val letter = charArrayOf('P', 'C', 'B', 'U')[(a and 0xC0) shr 6]
        val d1 = (a and 0x30) shr 4
        val d2 = a and 0x0F
        val d3 = (b and 0xF0) shr 4
        val d4 = b and 0x0F
        return "%c%d%X%X%X".format(letter, d1, d2, d3, d4)
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
