package com.openauto.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Live OBD-II telemetry values (0 when unknown). */
data class ObdData(
    val speedKmh: Int = 0,
    val rpm: Int = 0,
    val coolantTempC: Int = 0,
    val intakeTempC: Int = 0,
    val throttlePct: Int = 0,
    val engineLoadPct: Int = 0,
    val fuelLevelPct: Int = 0,
    val voltage: Double = 0.0
)

/** Connection lifecycle for the ELM327 adapter. */
enum class ObdConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** No link and nothing in progress: the state in which "Connect" makes sense. */
val ObdConnectionState.isIdle: Boolean get() = this == ObdConnectionState.DISCONNECTED || this == ObdConnectionState.ERROR

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

    private const val PREFS = "obd_prefs"
    private const val KEY_MAC = "obd_device_mac"

    // Serializes all adapter I/O: the 500ms poll loop and Scan/Clear must not
    // hit the single RFCOMM socket at the same time (garbled replies / errors).
    private val commandMutex = Mutex()

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
            runCatching { adapter.cancelDiscovery() }
            val newSocket = openSocket(device) ?: run {
                _connectionState.value = ObdConnectionState.ERROR
                return@withContext false
            }

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

    /**
     * Opens an RFCOMM socket to the adapter, trying (like Torque) the secure
     * SPP channel, then the insecure channel, then a reflection fallback on
     * channel 1 — clone ELM327 adapters fail one but succeed on another.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        tryConnect(runCatching { device.createRfcommSocketToServiceRecord(SPP_UUID) }.getOrNull())
            ?.let { return it }
        tryConnect(runCatching { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }.getOrNull())
            ?.let { return it }
        val reflected = runCatching {
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
        }.getOrNull()
        return tryConnect(reflected)
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(candidate: BluetoothSocket?): BluetoothSocket? {
        candidate ?: return null
        return try {
            candidate.connect()
            candidate
        } catch (e: IOException) {
            runCatching { candidate.close() }
            null
        }
    }

    /** Sends the standard ELM327 initialization sequence. */
    private fun initializeAdapter() {
        sendCommand("ATZ")   // reset
        Thread.sleep(1000)   // clone adapters need a moment after reset
        sendCommand("ATE0")  // echo off
        sendCommand("ATL0")  // line feeds off
        sendCommand("ATSP0") // automatic protocol selection
        sendCommand("0100")  // probe supported PIDs (wakes the ECU link)
    }

    /** Paired Bluetooth devices as (name, MAC) pairs, for the adapter picker. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Pair<String, String>> {
        val context = appContext ?: return emptyList()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.map { device ->
                (runCatching { device.name }.getOrNull() ?: "Unknown device") to device.address
            }.sortedBy { it.first.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** The adapter the user picked, or null if none chosen yet. */
    fun savedDeviceAddress(): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_MAC, null)

    fun saveDeviceAddress(address: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_MAC, address)?.apply()
    }

    /** Polls speed, RPM and coolant temperature once, updating [data]. */
    suspend fun poll(): Unit = withContext(Dispatchers.IO) {
        if (_connectionState.value != ObdConnectionState.CONNECTED) return@withContext
        commandMutex.withLock { pollLocked() }
    }

    private fun pollLocked() {
        val speed = sendCommand("010D")?.let { ObdParser.parseSpeed(it) }
        val rpm = sendCommand("010C")?.let { ObdParser.parseRpm(it) }
        val coolant = sendCommand("0105")?.let { ObdParser.parseCoolant(it) }
        val intake = sendCommand("010F")?.let { ObdParser.tempFrom(it, "410F") }
        val throttle = sendCommand("0111")?.let { ObdParser.percentFrom(it, "4111") }
        val load = sendCommand("0104")?.let { ObdParser.percentFrom(it, "4104") }
        val fuel = sendCommand("012F")?.let { ObdParser.percentFrom(it, "412F") }
        // Prefer the ECU's control-module voltage (PID 0142) — it reads the real
        // bus voltage. Many ELM327 clones report a miscalibrated ATRV (e.g. 16.9V
        // when the bus is ~14.5V), so ATRV is only a fallback when 0142 is
        // unsupported.
        val volt = sendCommand("0142")?.let { ObdParser.parseControlModuleVoltage(it) }
            ?: sendCommand("ATRV")?.let { ObdParser.parseVoltage(it) }

        _data.value = _data.value.copy(
            speedKmh = speed ?: _data.value.speedKmh,
            rpm = rpm ?: _data.value.rpm,
            coolantTempC = coolant ?: _data.value.coolantTempC,
            intakeTempC = intake ?: _data.value.intakeTempC,
            throttlePct = throttle ?: _data.value.throttlePct,
            engineLoadPct = load ?: _data.value.engineLoadPct,
            fuelLevelPct = fuel ?: _data.value.fuelLevelPct,
            voltage = volt ?: _data.value.voltage
        )
    }

    /**
     * Reads stored Diagnostic Trouble Codes (OBD mode 03). Returns the decoded
     * code list (e.g. "P0133"), empty if none, or a failure with a message.
     */
    suspend fun readTroubleCodes(): Result<List<String>> = withContext(Dispatchers.IO) {
        if (_connectionState.value != ObdConnectionState.CONNECTED) {
            return@withContext Result.failure(IllegalStateException("OBD not connected"))
        }
        commandMutex.withLock {
            val response = sendCommand("03")
                ?: return@withLock Result.failure(IllegalStateException("No response from adapter"))
            Result.success(ObdParser.parseDtcs(response))
        }
    }

    /** Clears stored trouble codes and turns off the MIL (OBD mode 04). */
    suspend fun clearTroubleCodes(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_connectionState.value != ObdConnectionState.CONNECTED) {
            return@withContext Result.failure(IllegalStateException("OBD not connected"))
        }
        commandMutex.withLock {
            val raw = sendCommand("04")
                ?: return@withLock Result.failure(IllegalStateException("No response from adapter"))
            val r = raw.uppercase().trim()
            if (r.contains("44") || r.contains("OK")) {
                Result.success(Unit)
            } else {
                // Common cause: ignition must be ON (engine off) to clear codes.
                Result.failure(IllegalStateException("Adapter replied \"$raw\". Turn ignition ON (engine off) and retry."))
            }
        }
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
