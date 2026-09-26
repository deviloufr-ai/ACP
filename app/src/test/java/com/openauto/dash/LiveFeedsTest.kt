package com.openauto.dash

import com.openauto.dash.LocationFeed.SpeedReading
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveFeedsTest {

    @Test
    fun gpsFixPublishesItsSpeedInKmh() {
        assertEquals(SpeedReading.Kmh(90), LocationFeed.speedReading(hasFix = true, hasSpeed = true, speedMps = 25f, ageMs = 0L))
        assertEquals(SpeedReading.Kmh(0), LocationFeed.speedReading(hasFix = true, hasSpeed = true, speedMps = 0f, ageMs = 1_000L))
    }

    @Test
    fun networkFixWithoutSpeedLeavesTheReadoutAlone() {
        // A Wi-Fi / cell fix between two GPS fixes must not read as "0 km/h".
        assertEquals(SpeedReading.Keep, LocationFeed.speedReading(hasFix = true, hasSpeed = false, speedMps = 0f, ageMs = 0L))
    }

    @Test
    fun staleOrMissingFixClearsTheSpeed() {
        assertEquals(SpeedReading.None, LocationFeed.speedReading(hasFix = false, hasSpeed = false, speedMps = 0f, ageMs = Long.MAX_VALUE))
        assertEquals(SpeedReading.None, LocationFeed.speedReading(hasFix = true, hasSpeed = true, speedMps = 20f, ageMs = 5_000L))
        assertEquals(SpeedReading.None, LocationFeed.speedReading(hasFix = true, hasSpeed = false, speedMps = 0f, ageMs = 60_000L))
    }
}
