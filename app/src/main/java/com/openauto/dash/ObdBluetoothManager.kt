package com.openauto.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
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
    val voltage: Double = 0.0,
    /**
     * [voltage] came from the engine computer (PID 0142), not the adapter's own
     * ATRV. Only the former is trusted for alerts: clone adapters misread ATRV.
     */
    val voltageFromEcu: Boolean = false
)

/** The engine warning lamp as the engine computer reports it (PID 0101). */
data class EngineLamp(val on: Boolean, val storedCodes: Int)

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

    private const val TAG = "Obd"

    /** Well-known SPP UUID used by ELM327 clones. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Upper bound for a single command's reply read, in milliseconds. */
    private const val READ_TIMEOUT_MS = 2000L

    /** Fault-code requests are slower: several computers may answer, and clones take their time. */
    private const val DTC_TIMEOUT_MS = 6000L

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

    /** The engine lamp from the last fault-code scan; null until one ran or if the car didn't say. */
    private val _lamp = MutableStateFlow<EngineLamp?>(null)
    val lamp: StateFlow<EngineLamp?> = _lamp.asStateFlow()

    /** Codes of the last scan that are only pending (seen, not yet confirmed by the engine computer). */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    /** Why the last connection attempt failed, in the user's language; null after a success or before any attempt. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private fun fail(@StringRes reason: Int, vararg args: Any): Boolean {
        _lastError.value = appContext?.let { if (args.isEmpty()) it.getString(reason) else it.getString(reason, *args) }
        return false
    }

    /** The saved adapter as the picker showed it: its Bluetooth name, else its address; null when none was chosen. */
    @SuppressLint("MissingPermission")
    fun savedDeviceLabel(): String? {
        val address = savedDeviceAddress() ?: return null
        return bondedDevices().firstOrNull { it.second == address }?.first ?: address
    }

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Connects to the adapter at [deviceAddress] (a Bluetooth MAC).
     * Returns true on success. Requires BLUETOOTH_CONNECT at runtime (API 31+).
     *
     * One attempt at a time: the resume observer and the 5 s retry loop both
     * call this, often in the same frame, and a second socket to an ELM327
     * (which takes one connection) knocks out the first. A link left from an
     * earlier try is closed first for the same reason, and whatever goes wrong
     * ends in ERROR, never stuck in CONNECTING where no retry would happen.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String): Boolean {
        if (_connectionState.value == ObdConnectionState.CONNECTED) return true
        if (!connectLock.tryLock()) return false
        try {
            if (_connectionState.value == ObdConnectionState.CONNECTED) return true
            _connectionState.value = ObdConnectionState.CONNECTING
            val ok = withContext(Dispatchers.IO) {
                // No poll or fault-code scan may talk to the link being replaced.
                commandMutex.withLock {
                    runCatching { open(deviceAddress) }
                        .onFailure {
                            Log.w(TAG, "connect failed", it)
                            if (it is SecurityException) fail(R.string.vehicle_err_permission)
                            else _lastError.value = it.message ?: it.javaClass.simpleName
                        }
                        .getOrDefault(false)
                }
            }
            if (!ok) closeQuietly() else _lastError.value = null
            // A demo started meanwhile owns the state; it hands back the real one when it ends.
            if (!DemoMode.isOn) _connectionState.value = if (ok) ObdConnectionState.CONNECTED else ObdConnectionState.ERROR
            return ok
        } finally {
            // Cancelled mid-attempt (the screen went away): a CONNECTING left
            // behind would stop every later retry.
            if (_connectionState.value == ObdConnectionState.CONNECTING) {
                closeQuietly()
                _connectionState.value = ObdConnectionState.ERROR
            }
            connectLock.unlock()
        }
    }

    private val connectLock = Mutex()

    @SuppressLint("MissingPermission")
    private fun open(deviceAddress: String): Boolean {
        val context = appContext ?: return false
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return fail(R.string.vehicle_err_bt_off)
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off")
            return fail(R.string.vehicle_err_bt_off)
        }
        closeQuietly()
        val device = adapter.getRemoteDevice(deviceAddress)
        val label = runCatching { device.name }.getOrNull() ?: deviceAddress
        // Only a paired adapter can be reached; an unpaired one fails slowly and says nothing.
        if (adapter.bondedDevices.none { it.address == deviceAddress }) {
            Log.w(TAG, "$deviceAddress is not paired")
            return fail(R.string.vehicle_err_not_paired, label)
        }
        runCatching { adapter.cancelDiscovery() }
        val newSocket = openSocket(device) ?: run {
            Log.w(TAG, "no RFCOMM channel to $deviceAddress accepted the connection")
            return fail(R.string.vehicle_err_refused, label)
        }
        socket = newSocket
        inputStream = newSocket.inputStream
        outputStream = newSocket.outputStream
        // A socket nothing answers on is no adapter: fail, so it is tried again.
        if (!initializeAdapter()) {
            Log.w(TAG, "$deviceAddress connected but never answered")
            return fail(R.string.vehicle_err_silent, label)
        }
        return true
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

    /**
     * [BluetoothSocket.connect] has no timeout of its own and can sit for half
     * a minute on an adapter that is unpowered or taken: a watchdog closes the
     * socket after [CONNECT_TIMEOUT_MS], which makes the connect return.
     */
    @SuppressLint("MissingPermission")
    private fun tryConnect(candidate: BluetoothSocket?): BluetoothSocket? {
        candidate ?: return null
        val watchdog = Thread {
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS)
                Log.w(TAG, "connect timed out after ${CONNECT_TIMEOUT_MS} ms")
                runCatching { candidate.close() }
            } catch (e: InterruptedException) {
                // The connect returned first: nothing to close.
            }
        }.apply { isDaemon = true; start() }
        return try {
            candidate.connect()
            watchdog.interrupt()
            if (candidate.isConnected) candidate else null
        } catch (e: IOException) {
            watchdog.interrupt()
            Log.w(TAG, "connect: ${e.message}")
            runCatching { candidate.close() }
            null
        }
    }

    /** Per RFCOMM channel tried; three channels, so an attempt takes at most three times this. */
    private const val CONNECT_TIMEOUT_MS = 8_000L

    /** Sends the standard ELM327 initialization sequence; false if the adapter said nothing at all. */
    private fun initializeAdapter(): Boolean {
        val replies = listOf(
            sendCommand("ATZ").also { Thread.sleep(1000) }, // reset; clone adapters need a moment after it
            sendCommand("ATE0"),  // echo off
            sendCommand("ATL0"),  // line feeds off
            sendCommand("ATSP0")  // automatic protocol selection
        )
        if (replies.all { it == null }) return false
        sendCommand("0100")  // probe supported PIDs (wakes the ECU link)
        return outputStream != null
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
                (runCatching { device.name }.getOrNull() ?: context.getString(R.string.vehicle_unknown_device)) to device.address
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
        if (DemoMode.isOn || _connectionState.value != ObdConnectionState.CONNECTED) return@withContext
        commandMutex.withLock {
            if (_connectionState.value == ObdConnectionState.CONNECTED) pollLocked()
        }
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
        val ecuVolt = sendCommand("0142")?.let { ObdParser.parseControlModuleVoltage(it) }
        val volt = ecuVolt ?: sendCommand("ATRV")?.let { ObdParser.parseVoltage(it) }

        _data.value = _data.value.copy(
            speedKmh = speed ?: _data.value.speedKmh,
            rpm = rpm ?: _data.value.rpm,
            coolantTempC = coolant ?: _data.value.coolantTempC,
            intakeTempC = intake ?: _data.value.intakeTempC,
            throttlePct = throttle ?: _data.value.throttlePct,
            engineLoadPct = load ?: _data.value.engineLoadPct,
            fuelLevelPct = fuel ?: _data.value.fuelLevelPct,
            voltage = volt ?: _data.value.voltage,
            voltageFromEcu = if (volt != null) ecuVolt != null else _data.value.voltageFromEcu
        )
        BatteryWatch.feed(_data.value, System.currentTimeMillis())
    }

    /**
     * Reads stored Diagnostic Trouble Codes (OBD mode 03). Returns the decoded
     * code list (e.g. "P0133"), empty if none, or a failure with a message.
     */
    suspend fun readTroubleCodes(): Result<List<String>> = withContext(Dispatchers.IO) {
        if (DemoMode.isOn) return@withContext DemoMode.scanCodes()
        if (_connectionState.value != ObdConnectionState.CONNECTED) {
            return@withContext failure(R.string.vehicle_obd_not_connected)
        }
        commandMutex.withLock {
            fun ask(command: String, timeoutMs: Long = DTC_TIMEOUT_MS): String? = sendCommand(command, timeoutMs)
            val stored = linkedSetOf<String>()
            val pending = linkedSetOf<String>()
            var answered = false
            fun collect(reply: String?, mode: Int) {
                val codes = reply?.let { ObdParser.parseDtcReply(it, mode) } ?: return
                if (mode == 0x43) answered = true
                (if (mode == 0x43) stored else pending) += codes
            }
            try {
                // Give slow computers time: adaptive timing can cut the wait short,
                // and a busy running engine then reads as "NO DATA".
                ask("ATAT0", READ_TIMEOUT_MS)
                ask("ATSTFF", READ_TIMEOUT_MS)
                // What the engine computer itself says: lamp on or off, so an
                // empty read can be checked against it.
                _lamp.value = ask("0101")?.let { ObdParser.parseEngineLamp(it) }
                collect(ask("03"), 0x43)
                collect(ask("07"), 0x47)
                // On CAN, also ask the engine computer on its own address: with
                // everyone answering at once, its reply can be the one lost.
                if (ask("ATDPN", READ_TIMEOUT_MS)?.let(ObdParser::isCan11Bit) == true &&
                    ask("ATSH7E0", READ_TIMEOUT_MS)?.contains("OK") == true
                ) {
                    collect(ask("03"), 0x43)
                    collect(ask("07"), 0x47)
                    ask("ATSH7DF", READ_TIMEOUT_MS)
                }
            } finally {
                ask("ATAT1", READ_TIMEOUT_MS)
                ask("ATST32", READ_TIMEOUT_MS)
            }
            if (!answered) return@withLock failure(R.string.vehicle_no_dtc_answer)
            _pending.value = pending - stored
            Result.success((stored + pending).toList())
        }
    }

    /**
     * One read request for the experimental reading finder: [request] bytes
     * ("221A5B"), addressed to [header] (a CAN address such as 7E0) or to
     * everyone when null. The functional address is put back afterwards so the
     * regular polling is unaffected. Null when not connected or unanswered.
     *
     * A computer off the OBD addresses answers from its own [replyAddress]
     * (6A8 answers on 688): the adapter only listens to 7E8-7EF unless told,
     * and a long answer needs flow control sent back to [header]. A [session]
     * ("10C0") is opened first; it lapses by itself a few seconds later.
     */
    suspend fun query(
        header: String?,
        request: String,
        timeoutMs: Long = READ_TIMEOUT_MS,
        replyAddress: String? = null,
        session: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (DemoMode.isOn || _connectionState.value != ObdConnectionState.CONNECTED) return@withContext null
        commandMutex.withLock {
            if (_connectionState.value != ObdConnectionState.CONNECTED) return@withLock null
            val ownAddresses = header != null && replyAddress != null
            try {
                if (header != null) sendCommand("ATSH$header", READ_TIMEOUT_MS)
                if (ownAddresses) {
                    sendCommand("ATCRA$replyAddress", READ_TIMEOUT_MS)
                    sendCommand("ATFCSH$header", READ_TIMEOUT_MS)
                    sendCommand("ATFCSD300000", READ_TIMEOUT_MS)
                    sendCommand("ATFCSM1", READ_TIMEOUT_MS)
                }
                if (header != null && session != null) sendCommand(session, timeoutMs)
                sendCommand(request, timeoutMs)
            } finally {
                if (ownAddresses) {
                    sendCommand("ATFCSM0", READ_TIMEOUT_MS)
                    sendCommand("ATCRA", READ_TIMEOUT_MS)
                }
                if (header != null) sendCommand("ATSH7DF", READ_TIMEOUT_MS)
            }
        }
    }

    /** Clears stored trouble codes and turns off the MIL (OBD mode 04). */
    suspend fun clearTroubleCodes(): Result<Unit> = withContext(Dispatchers.IO) {
        if (DemoMode.isOn) return@withContext DemoMode.clearCodes()
        if (_connectionState.value != ObdConnectionState.CONNECTED) {
            return@withContext failure(R.string.vehicle_obd_not_connected)
        }
        commandMutex.withLock {
            val raw = sendCommand("04", DTC_TIMEOUT_MS)
                ?: return@withLock failure(R.string.vehicle_no_response)
            val r = raw.uppercase().trim()
            if (r.contains("44") || r.contains("OK")) {
                Result.success(Unit)
            } else {
                // Common cause: ignition must be ON (engine off) to clear codes.
                failure(R.string.vehicle_clear_rejected, raw)
            }
        }
    }

    /** A failed [Result] whose message is shown in the UI, so it is localized. */
    private fun failure(@StringRes message: Int, vararg args: Any): Result<Nothing> {
        val context = appContext
        val text = when {
            context == null -> "OBD error"
            args.isEmpty() -> context.getString(message)
            else -> context.getString(message, *args)
        }
        return Result.failure(IllegalStateException(text))
    }

    /**
     * Writes a command and reads the reply up to the ELM327 '>' prompt.
     *
     * Reads are bounded by [timeoutMs]: a silent or misbehaving adapter
     * would otherwise block this IO coroutine indefinitely on [InputStream.read].
     */
    private fun sendCommand(command: String, timeoutMs: Long = READ_TIMEOUT_MS): String? {
        val out = outputStream ?: return null
        val input = inputStream ?: return null
        return try {
            val buffer = ByteArray(1024)
            // A reply that came in after its command gave up would otherwise be
            // read as the answer to this one.
            while (input.available() > 0) input.read(buffer)
            out.write((command + "\r").toByteArray())
            out.flush()

            val response = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
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
            // The link is gone: drop it so the retry opens a fresh one.
            Log.w(TAG, "$command: ${e.message}")
            closeQuietly()
            if (_connectionState.value == ObdConnectionState.CONNECTED) _connectionState.value = ObdConnectionState.ERROR
            null
        }
    }

    /** [DemoMode]'s readings, shown as if an adapter were connected. */
    internal fun demoWrite(data: ObdData, lamp: EngineLamp?, pending: Set<String>) {
        _connectionState.value = ObdConnectionState.CONNECTED
        _data.value = data
        _lamp.value = lamp
        _pending.value = pending
    }

    /** The demo is over: back to the real link, whose next poll fills the readings in again. */
    internal fun endDemo(lamp: EngineLamp?, pending: Set<String>) {
        val linked = socket?.isConnected == true
        _connectionState.value = if (linked) ObdConnectionState.CONNECTED else ObdConnectionState.DISCONNECTED
        _data.value = ObdData()
        _lamp.value = lamp
        _pending.value = pending
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
