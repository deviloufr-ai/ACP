package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The sun's altitude and the day/night switch built on it. */
class DayNightTest {

    private val paris = 48.8566 to 2.3522
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun noonInJuneIsHighAndMidnightIsLow() {
        val (lat, lon) = paris
        val noon = sunAltitudeDeg(at(2025, 6, 21, 12), lat, lon)
        val midnight = sunAltitudeDeg(at(2025, 6, 21, 0), lat, lon)
        // Solstice noon over Paris: about 64.6° up; midnight about -17.7°.
        assertTrue("noon $noon", noon in 63.0..66.0)
        assertTrue("midnight $midnight", midnight in -19.0..-16.0)
    }

    @Test
    fun winterEveningIsNight() {
        val (lat, lon) = paris
        // 21 December, 17:30 UTC (18:30 in Paris): sunset was at 16:56 UTC, civil twilight over by 17:35 or so.
        assertFalse(sunUp(at(2025, 12, 21, 17, 45), lat, lon))
        // 12:00 UTC is day even at the solstice.
        assertTrue(sunUp(at(2025, 12, 21, 12), lat, lon))
    }

    @Test
    fun summerEveningStaysLightUntilLate() {
        val (lat, lon) = paris
        // 21 June, 20:00 UTC (22:00 in Paris): sunset at 19:58 UTC, so still civil twilight.
        assertTrue(sunUp(at(2025, 6, 21, 20), lat, lon))
        // 21:30 UTC (23:30 in Paris) is dark.
        assertFalse(sunUp(at(2025, 6, 21, 21, 30), lat, lon))
    }

    @Test
    fun withoutAPositionTheClockDecides() {
        assertTrue(sunUp(at(2025, 1, 10, 9), null, null, utc))
        assertFalse(sunUp(at(2025, 1, 10, 22), null, null, utc))
        assertFalse(sunUp(at(2025, 1, 10, 6, 30), null, null, utc))
    }
}
