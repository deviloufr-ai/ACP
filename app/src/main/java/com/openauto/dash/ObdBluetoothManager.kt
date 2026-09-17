package com.openauto.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Live OBD-II telemetry values (0 when unknown). */
data class ObdData(
    val speedKmh: Int = 0,
    val rpm: Int = 0,
    val coolantTempC: Int = 0
)

/** Connection lifecycle for the ELM327 adapter. */
enum class ObdConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Singleton manager for OBD-II telemetry over a Bluetooth ELM327 adapter.
 *
 * Connects to the adapter over the standard Serial Port Profile (SPP) RFCOMM
 * channel, issues AT setup commands, and polls the standard PIDs for speed
 * (010D), RPM (010C) and coolant temperature (0105).
 *
 * All socket work runs on [Dispatchers.IO]; callers observe [data] and
 * [connectionState] from the UI.
 */
object ObdBluetoothManager {

    /** Well-known SPP UUID used by ELM327 clones. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Upper bound for a single command's reply read, in milliseconds. */
    private const val READ_TIMEOUT_MS = 2000L

    private var appContext: Context? = null
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val _data = MutableStateFlow(ObdData())
    val data: StateFlow<ObdData> = _data.asStateFlow()

    private val _connectionState = MutableStateFlow(ObdConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Connects to the adapter at [deviceAddress] (a Bluetooth MAC).
     * Returns true on success. Requires BLUETOOTH_CONNECT at runtime (API 31+).
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String): Boolean = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext false
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return@withContext false
        val adapter = manager.adapter ?: return@withContext false

        _connectionState.value = ObdConnectionState.CONNECTING
        try {
            val device = adapter.getRemoteDevice(deviceAddress)
            adapter.cancelDiscovery()
            val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            newSocket.connect()

            socket = newSocket
            inputStream = newSocket.inputStream
            outputStream = newSocket.outputStream
            initializeAdapter()

            _connectionState.value = ObdConnectionState.CONNECTED
            true
        } catch (e: IOException) {
            _connectionState.value = ObdConnectionState.ERROR
            closeQuietly()
            false
        } catch (e: SecurityException) {
            _connectionState.value = ObdConnectionState.ERROR
            closeQuietly()
            false
        }
    }

    /** Sends the standard ELM327 initialization sequence. */
    private fun initializeAdapter() {
        sendCommand("ATZ")   // reset
        sendCommand("ATE0")  // echo off
        sendCommand("ATL0")  // line feeds off
        sendCommand("ATSP0") // automatic protocol selection
    }

    /** Polls speed, RPM and coolant temperature once, updating [data]. */
    suspend fun poll(): Unit = withContext(Dispatchers.IO) {
        if (_connectionState.value != ObdConnectionState.CONNECTED) return@withContext

        val speed = sendCommand("010D")?.let { parseSpeed(it) }
        val rpm = sendCommand("010C")?.let { parseRpm(it) }
        val coolant = sendCommand("0105")?.let { parseCoolant(it) }

        _data.value = _data.value.copy(
            speedKmh = speed ?: _data.value.speedKmh,
            rpm = rpm ?: _data.value.rpm,
            coolantTempC = coolant ?: _data.value.coolantTempC
        )
    }

    /**
     * Writes a command and reads the reply up to the ELM327 '>' prompt.
     *
     * Reads are bounded by [READ_TIMEOUT_MS]: a silent or misbehaving adapter
     * would otherwise block this IO coroutine indefinitely on [InputStream.read].
     */
    private fun sendCommand(command: String): String? {
        val out = outputStream ?: return null
        val input = inputStream ?: return null
        return try {
            out.write((command + "\r").toByteArray())
            out.flush()

            val response = StringBuilder()
            val buffer = ByteArray(1024)
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    response.append(String(buffer, 0, read))
                    if (response.contains(">")) break
                } else {
                    Thread.sleep(20)
                }
            }
            response.toString().replace(">", "").trim().ifEmpty { null }
        } catch (e: IOException) {
            _connectionState.value = ObdConnectionState.ERROR
            null
        }
    }

    // --- PID response parsing -------------------------------------------------
    // Responses look like "41 0D 32" for the query "010D". The mode byte is
    // 0x40 + request mode (0x41), followed by the PID and its data bytes.

    private fun parseSpeed(response: String): Int? {
        val bytes = dataBytes(response, "410D") ?: return null
        return bytes.firstOrNull()
    }

    private fun parseRpm(response: String): Int? {
        val bytes = dataBytes(response, "410C") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4
    }

    private fun parseCoolant(response: String): Int? {
        val bytes = dataBytes(response, "4105") ?: return null
        val a = bytes.firstOrNull() ?: return null
        return a - 40
    }

    /** Extracts the data bytes that follow [header] (e.g. "410D") in [response]. */
    private fun dataBytes(response: String, header: String): List<Int>? {
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

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        closeQuietly()
        _connectionState.value = ObdConnectionState.DISCONNECTED
        _data.value = ObdData()
    }

    private fun closeQuietly() {
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        inputStream = null
        outputStream = null
        socket = null
    }
}
