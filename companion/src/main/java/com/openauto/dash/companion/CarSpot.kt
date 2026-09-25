package com.openauto.dash.companion

import android.content.Context
import com.openauto.dash.link.CarLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the car was last seen: the last place it stopped, or the spot saved on its dashboard. */
data class CarSpotInfo(val lat: Double, val lng: Double, val at: Long, val saved: Boolean)

/**
 * The car's last known position, as the head unit reports it over the link
 * ([CarLocation]). Kept in private preferences so it is still there once the
 * car is switched off and the link is gone, which is when it is wanted.
 */
object CarSpot {
    private const val PREFS = "car_spot"

    private val _spot = MutableStateFlow<CarSpotInfo?>(null)
    val spot: StateFlow<CarSpotInfo?> = _spot
    private var loaded = false

    @Synchronized
    fun load(context: Context): CarSpotInfo? {
        if (!loaded) {
            val p = prefs(context)
            _spot.value = if (!p.contains("lat")) null else CarSpotInfo(
                lat = java.lang.Double.longBitsToDouble(p.getLong("lat", 0L)),
                lng = java.lang.Double.longBitsToDouble(p.getLong("lng", 0L)),
                at = p.getLong("at", 0L),
                saved = p.getBoolean("saved", false)
            )
            loaded = true
        }
        return _spot.value
    }

    /** Takes [report] unless an even newer position is already known (reports can arrive out of order on reconnect). */
    @Synchronized
    fun update(context: Context, report: CarLocation) {
        val current = load(context)
        if (current != null && current.at > report.at) return
        val next = CarSpotInfo(report.lat, report.lng, report.at, report.saved)
        if (next == current) return
        _spot.value = next
        prefs(context).edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(next.lat))
            .putLong("lng", java.lang.Double.doubleToRawLongBits(next.lng))
            .putLong("at", next.at)
            .putBoolean("saved", next.saved)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
