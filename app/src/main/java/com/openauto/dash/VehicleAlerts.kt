package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The bar's vehicle alerts, in two levels. A warning (amber) is a reading out
 * of range: it shows while the reading is, then goes by itself. A critical
 * alert (red) is one to stop the car for: it is said once through the car's
 * voice, and it stays on the bar until the driver taps it, even after the
 * reading recovers, so a fault seen at 90 km/h is still there at the next
 * red light.
 */

internal enum class AlertLevel { WARNING, CRITICAL }

/** One alert: [key] identifies the reading (one chip per reading), [text] is what the chip and the voice say. */
internal data class VehicleAlert(val key: String, val level: AlertLevel, val icon: ImageVector, val text: String)

/** The alerts [obdData] calls for right now, worst first. */
internal fun vehicleAlerts(context: Context, obdData: ObdData): List<VehicleAlert> {
    val alerts = ArrayList<VehicleAlert>(2)
    val volts = obdData.voltage
    // 0.0 is "no reading yet", not a flat battery.
    if (volts > 0.0 && volts !in BATTERY_OK_V) {
        val level = if (volts in BATTERY_WARNING_V) AlertLevel.WARNING else AlertLevel.CRITICAL
        alerts += VehicleAlert(KEY_BATTERY, level, Icons.Filled.BatteryAlert, context.getString(R.string.dash_alert_battery, volts))
    }
    val temp = obdData.coolantTempC
    if (temp >= COOLANT_WARNING_C) {
        val level = if (temp >= COOLANT_CRITICAL_C) AlertLevel.CRITICAL else AlertLevel.WARNING
        alerts += VehicleAlert(KEY_COOLANT, level, Icons.Filled.Thermostat, context.getString(R.string.dash_alert_coolant, temp))
    }
    return alerts.sortedByDescending { it.level }
}

internal const val KEY_BATTERY = "battery"
internal const val KEY_COOLANT = "coolant"

/** Critical alerts raised and not yet acknowledged; the bar draws these whatever the reading does next. */
internal object AlertCenter {
    /** By key: the alert as it was when raised (the text keeps the reading that triggered it). */
    val critical = mutableStateMapOf<String, VehicleAlert>()

    /** Puts [alert] up if it is not up already, saying it once through the car's voice when it is new. */
    fun raise(context: Context, alert: VehicleAlert, @StringRes spokenRes: Int) {
        if (critical.containsKey(alert.key)) return
        critical[alert.key] = alert
        val config = AiSettings.load(context)
        if (config.speak) {
            CarVoice.setContext(context)
            val resources = config.language.resources(context)
            CarVoice.speak(resources.getString(spokenRes, alert.text), config.language.locale)
        }
    }

    /** The driver saw it. The same fault raised again later is said again. */
    fun acknowledge(key: String) {
        critical.remove(key)
    }
}
