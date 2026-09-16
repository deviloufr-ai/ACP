package com.openauto.dash

import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.toInt

/**
 * Singleton Manager for OBD-II Bluetooth communication with ELM327 adapters.
 */
object ObdBluetoothManager {
    private const val RFCOMM_CHANNEL = "1"

    fun getSpeedKmH(): Flow<Int> = callbackFlow { trySend(0) }

    fun getRpm(): Flow<Int> = callbackFlow { trySend(0) }

    fun getCoolantTemp(): Flow<Int> = callbackFlow { trySend(0) }

    suspend fun readDTCs(): List<String>? = null

    suspend fun clearDTCs(): Boolean = false

    suspend fun connect(adapterMac: String, adapterName: String): BluetoothSocket? {
        return try {
            val bluetoothManager = context?.bluetoothManager ?: return null
            val device = bluetoothManager.getRemoteDevice(adapterMac)
            val socket = device.createRfcommSocketToServiceRecord(UUID.randomUUID().toString())
            socket.connect()
            initializeAdapter(socket.outputStream, socket)
            socket
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun disconnect(socket: BluetoothSocket?) {
        socket?.close()
    }

    private suspend fun sendAtCommand(socket: BluetoothSocket, command: String): String? {
        try {
            val outputStream = socket.outputStream
            val input = socket.inputStream
            outputStream.write((command + "\n").toByteArray())
            outputStream.flush()
            val buffer = ByteArray(4096)
            var bytesRead = input.read(buffer)
            val result = StringBuilder()
            
            while (bytesRead > 0 && !result.toString().contains("\r\n")) {
                result.append(String(buffer, 0, bytesRead))
                bytesRead = input.read(buffer)
            }
            
            return if (result.toString().startsWith("OK\r\n")) null else result.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun initializeAdapter(outputStream: android.os.Parcelable?, socket: BluetoothSocket?) {
        sendAtCommand(socket, "ATZ")?.let { println("Reset: $it") }
        sendAtCommand(socket, "AT SP 0")?.let { println("Parity: $it") }
        sendAtCommand(socket, "ATE0")?.let { println("Echo: $it") }
    }

    private fun parseSpeed(response: String): Int {
        return try {
            val hexData = response.substringAfter("02+").substringBefore("+") + 
                          response.substringAfter("02+").substringAfter("+").substringBefore("+")
            Integer.parseInt(hexData, 16) / 2
        } catch (e: Exception) {
            0
        }
    }

    private fun parseRpm(response: String): Int {
        return try {
            val hexData = response.substringAfter("02+").substringBefore("+") + 
                          response.substringAfter("02+").substringAfter("+").substringBefore("+")
            ((Integer.parseInt(hexData.substring(0, 2), 16) * 256 + 
              Integer.parseInt(hexData.substring(2, 4), 16)) / 4).coerceIn(0, 10000)
        } catch (e: Exception) {
            0
        }
    }

    private fun parseCoolantTemp(response: String): Int {
        return try {
            val hexData = response.substringAfter("02+").substringBefore("+") + 
                          response.substringAfter("02+").substringAfter("+").substringBefore("+")
            (Integer.parseInt(hexData, 16) - 40).toInt().coerceIn(-40, 150)
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        private var context: Context? = null
        
        fun setContext(context: Context) {
            ObdBluetoothManager.context = context
        }
    }
}
