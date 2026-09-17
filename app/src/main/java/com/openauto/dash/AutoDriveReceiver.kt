package com.openauto.dash

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Version-safe read of the [BluetoothDevice] extra (typed getter deprecated on API 33+). */
private fun Intent.bluetoothDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

/**
 * BroadcastReceiver that auto-launches MainActivity when car Bluetooth connects.
 * Enables the app to act as an auto-trigger phone dashboard.
 *
 * Note: `ACTION_ACL_CONNECTED`/`ACTION_ACL_DISCONNECTED` are implicit broadcasts
 * that Android 8+ (API 26) no longer delivers to manifest-declared receivers, so
 * on this app's minSdk (29) auto-launch only fires when the process is already
 * running and this receiver has been registered dynamically. A foreground
 * service would be required to catch it while backgrounded.
 */
class AutoDriveReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AutoDriveReceiver"
        
        /** Key for stored car Bluetooth MAC address */
        const val PREF_CAR_BLUETOOTH_MAC = "car_bluetooth_mac"
        
        /** Key for storing connected devices */
        const val KEY_CONNECTED_DEVICE_MAC = "connected_device_mac"
        
        /** Key for storing connected device name */
        const val KEY_CONNECTED_DEVICE_NAME = "connected_device_name"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                handleBluetoothConnection(context, intent)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                // Handle disconnection if needed
                Log.d(TAG, "Bluetooth disconnected")
            }
        }
    }
    
    /**
     * Handle car Bluetooth connection - auto-launch MainActivity.
     */
    private fun handleBluetoothConnection(context: Context, intent: Intent) {
        val device = intent.bluetoothDevice() ?: return
        
        // Check if this is the saved car Bluetooth device
        val storedMac = context.getSharedPreferences(
            PREF_CAR_BLUETOOTH_MAC,
            Context.MODE_PRIVATE
        ).getString(KEY_CONNECTED_DEVICE_MAC, "")
        
        if (storedMac != null && storedMac == device.address) {
            Log.d(TAG, "Car Bluetooth connected: ${device.name} (${device.address})")
            
            // Auto-launch MainActivity as the car dashboard is now active
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            context.startActivity(launchIntent)
        } else {
            Log.d(TAG, "Connection from unknown device: $device")
        }
        
        // Store the connected device info
        storeConnectedDevice(context, device)
    }
    
    /**
     * Store currently connected Bluetooth device info.
     */
    private fun storeConnectedDevice(context: Context, device: BluetoothDevice) {
        val prefs = context.getSharedPreferences(
            PREF_CAR_BLUETOOTH_MAC,
            Context.MODE_PRIVATE
        )
        
        val bluetoothAdapter = 
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothAdapter ?: return
        
        val macAddress = device.address
        val deviceName = device.name ?: "Unknown"
        val bondState = device.bondState
        
        prefs.edit().apply {
            putString(KEY_CONNECTED_DEVICE_MAC, macAddress)
            putString(KEY_CONNECTED_DEVICE_NAME, deviceName)
            
            // Also store for future reference
            if (bondState == BluetoothDevice.BOND_BONDED) {
                putString("last_bonded_device_mac", macAddress)
                putString("last_bonded_device_name", deviceName)
            }
            
            apply()
        }
        
        Log.d(TAG, "Stored connection: $deviceName ($macAddress)")
    }
}

/**
 * Utility class to manage saved car Bluetooth connection.
 */
class CarBluetoothHelper {
    
    companion object {
        fun saveCarConnection(context: Context, mac: String, name: String) {
            val prefs = context.getSharedPreferences(
                AutoDriveReceiver.PREF_CAR_BLUETOOTH_MAC,
                Context.MODE_PRIVATE
            )
            prefs.edit().apply {
                putString(AutoDriveReceiver.KEY_CONNECTED_DEVICE_MAC, mac)
                putString(AutoDriveReceiver.KEY_CONNECTED_DEVICE_NAME, name)
                apply()
            }
        }
        
        fun getSavedMac(context: Context): String? {
            return context.getSharedPreferences(
                AutoDriveReceiver.PREF_CAR_BLUETOOTH_MAC,
                Context.MODE_PRIVATE
            ).getString(AutoDriveReceiver.KEY_CONNECTED_DEVICE_MAC, null)
        }
    }
}
