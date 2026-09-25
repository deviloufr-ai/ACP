package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.runtime.mutableStateListOf
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

/** One alert as it happened, for the log the fault-code tile shows. */
internal data class AlertEvent(val at: Long, val level: AlertLevel, val text: String)

/** Critical alerts raised and not yet acknowledged; the bar draws these whatever the reading does next. */
internal object AlertCenter {
    /** By key: the alert as it was when raised (the text keeps the reading that triggered it). */
    val critical = mutableStateMapOf<String, VehicleAlert>()
    /** The last alerts of either level, newest first, so a chip that came and went can still be read. */
    val history = mutableStateListOf<AlertEvent>()
    private const val HISTORY_MAX = 20
    private val activeWarnings = HashSet<String>()

    /** Logs the warnings in [warnings] that were not up a moment ago; forgets the ones that went. */
    fun noteWarnings(warnings: List<VehicleAlert>) {
        val keys = warnings.map { it.key }.toSet()
        warnings.forEach { if (activeWarnings.add(it.key)) log(it) }
        activeWarnings.retainAll(keys)
    }

    private fun log(alert: VehicleAlert) {
        history.add(0, AlertEvent(System.currentTimeMillis(), alert.level, alert.text))
        while (history.size > HISTORY_MAX) history.removeAt(history.lastIndex)
    }

    /** Puts [alert] up if it is not up already, saying it once through the car's voice when it is new. */
    fun raise(context: Context, alert: VehicleAlert, @StringRes spokenRes: Int) {
        if (critical.containsKey(alert.key)) return
        critical[alert.key] = alert
        log(alert)
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
