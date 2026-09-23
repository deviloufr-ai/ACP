package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trouble-code descriptions: table hits, case handling and the generic fallback. */
class ObdCodesTest {

    @Test
    fun knownCodeComesFromTheTable() {
        val info = ObdCodes.describe("P0505")
        assertEquals("P0505", info.code)
        assertEquals("Idle air control system", info.title)
        assertTrue(info.fix.isNotBlank())
        assertEquals(R.string.vehicle_dtc_p0505_title, info.titleRes)
        assertEquals(R.string.vehicle_dtc_p0505_fix, info.fixRes)
        assertTrue(info.titleParts.isEmpty())
    }

    @Test
    fun lookupIsCaseAndWhitespaceInsensitive() {
        assertEquals(ObdCodes.describe("P0505"), ObdCodes.describe(" p0505 "))
    }

    @Test
    fun unknownCodeGetsASystemAndAreaFallback() {
        val info = ObdCodes.describe("U0123")
        assertEquals("U0123", info.code)
        assertTrue(info.title, info.title.startsWith("Network generic fault"))
        assertTrue(info.title, info.title.contains("fuel & air metering"))
        assertTrue(info.fix.isNotBlank())
        assertEquals(R.string.vehicle_dtc_fallback_title, info.titleRes)
        assertEquals(R.string.vehicle_dtc_fallback_fix, info.fixRes)
        assertEquals(
            listOf(R.string.vehicle_dtc_system_network, R.string.vehicle_dtc_scope_generic, R.string.vehicle_dtc_area_fuel_air),
            info.titleParts
        )

        val manufacturer = ObdCodes.describe("P1300")
        assertTrue(manufacturer.title, manufacturer.title.startsWith("Powertrain manufacturer-specific fault"))
        assertTrue(manufacturer.title, manufacturer.title.contains("ignition / misfire"))
        assertEquals(
            listOf(R.string.vehicle_dtc_system_powertrain, R.string.vehicle_dtc_scope_manufacturer, R.string.vehicle_dtc_area_ignition),
            manufacturer.titleParts
        )
    }

    @Test
    fun tooShortCodeIsStillDescribed() {
        val info = ObdCodes.describe("P")
        assertEquals("Unknown code", info.title)
        assertEquals(R.string.vehicle_dtc_unknown_code, info.titleRes)
    }
}
