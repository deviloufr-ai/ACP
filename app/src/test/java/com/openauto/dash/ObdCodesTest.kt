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

        val manufacturer = ObdCodes.describe("P1300")
        assertTrue(manufacturer.title, manufacturer.title.startsWith("Powertrain manufacturer-specific fault"))
        assertTrue(manufacturer.title, manufacturer.title.contains("ignition / misfire"))
    }

    @Test
    fun tooShortCodeIsStillDescribed() {
        assertEquals("Unknown code", ObdCodes.describe("P").title)
    }
}
