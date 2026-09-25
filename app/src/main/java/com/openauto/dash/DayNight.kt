package com.openauto.dash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/*
 * Whether the sun is up where the car is, for the Auto appearance. Uses the
 * last GPS fix when any tile has one; without one, the clock alone with a
 * fixed daytime window, which is right most of the year in Europe.
 */

/** Civil twilight: the sun this far below the horizon is still "day" for the screen. */
private const val TWILIGHT_DEG = -6.0
/** Without a position: day from 7:00 to 19:59 local time. */
private const val FALLBACK_DAY_START_H = 7
private const val FALLBACK_DAY_END_H = 20

/** True while the sun is up here, re-read each minute. Never acquires the GPS itself. */
@Composable
internal fun rememberSunUp(): Boolean {
    val now = rememberWallClock(60_000L).longValue
    val location by LocationFeed.location.collectAsState()
    val lat = location?.latitude
    val lon = location?.longitude
    return remember(now, lat, lon) { sunUp(now, lat, lon) }
}

/**
 * Whether the sun is above civil twilight at [timeMs] at ([lat], [lon]); with
 * no position, whether the local hour is within the fallback day window.
 */
internal fun sunUp(timeMs: Long, lat: Double?, lon: Double?, zone: TimeZone = TimeZone.getDefault()): Boolean {
    if (lat == null || lon == null) {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour in FALLBACK_DAY_START_H until FALLBACK_DAY_END_H
    }
    return sunAltitudeDeg(timeMs, lat, lon) > TWILIGHT_DEG
}

/**
 * The sun's altitude above the horizon in degrees (NOAA's low-precision
 * algorithm, good to a fraction of a degree, plenty for a day/night switch).
 */
internal fun sunAltitudeDeg(timeMs: Long, lat: Double, lon: Double): Double {
    // Julian centuries since J2000.0.
    val jd = timeMs / 86_400_000.0 + 2_440_587.5
    val t = (jd - 2_451_545.0) / 36_525.0
    val meanLon = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
    val meanAnom = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
    val m = Math.toRadians(meanAnom)
    val centre = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
        sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
    val trueLon = meanLon + centre
    val omega = Math.toRadians(125.04 - 1934.136 * t)
    val apparentLon = Math.toRadians(trueLon - 0.00569 - 0.00478 * sin(omega))
    val obliquity = Math.toRadians(
        23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0 + 0.00256 * cos(omega)
    )
    val declination = asin(sin(obliquity) * sin(apparentLon))
    // Equation of time, in minutes.
    val y = tan(obliquity / 2).let { it * it }
    val l0 = Math.toRadians(meanLon)
    val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    val eqTime = 4 * Math.toDegrees(
        y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m)
    )
    // True solar time and hour angle.
    val minutesUtc = ((timeMs / 60_000.0) - floor(timeMs / 86_400_000.0) * 1_440.0)
    val trueSolarMinutes = (minutesUtc + eqTime + 4 * lon).mod(1_440.0)
    val hourAngle = Math.toRadians(trueSolarMinutes / 4 - 180.0)
    val phi = Math.toRadians(lat)
    val cosZenith = (sin(phi) * sin(declination) + cos(phi) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)
    return 90.0 - Math.toDegrees(acos(cosZenith))
}
