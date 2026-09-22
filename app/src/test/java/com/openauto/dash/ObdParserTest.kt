package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ELM327 reply decoding, including the echo / prompt noise real adapters add. */
class ObdParserTest {

    @Test
    fun speedIsTheSingleDataByte() {
        assertEquals(50, ObdParser.parseSpeed("41 0D 32"))
        // Echoed command, CR line ends and the ">" prompt are all ignored.
        assertEquals(50, ObdParser.parseSpeed("010D\r41 0D 32\r\r>"))
    }

    @Test
    fun rpmIsSixteenBitsOverFour() {
        assertEquals(1726, ObdParser.parseRpm("41 0C 1A F8"))
        assertNull(ObdParser.parseRpm("41 0C 1A"))
    }

    @Test
    fun temperaturesAreOffsetByForty() {
        assertEquals(83, ObdParser.parseCoolant("41 05 7B"))
        assertEquals(-40, ObdParser.tempFrom("41 0F 00", "410F"))
    }

    @Test
    fun percentagesScaleFromByte() {
        assertEquals(50, ObdParser.percentFrom("41 11 80", "4111"))
        assertEquals(100, ObdParser.percentFrom("41 04 FF", "4104"))
    }

    @Test
    fun voltageFromAdapterAndFromEcu() {
        assertEquals(12.6, ObdParser.parseVoltage("12.6V")!!, 1e-9)
        assertEquals(12.5, ObdParser.parseControlModuleVoltage("41 42 30 D4")!!, 1e-9)
    }

    @Test
    fun noDataYieldsNull() {
        assertNull(ObdParser.parseSpeed("NO DATA"))
        assertNull(ObdParser.parseSpeed("SEARCHING..."))
        assertNull(ObdParser.parseVoltage("?"))
    }

    @Test
    fun troubleCodesDecodeAndSkipPadding() {
        assertEquals(listOf("P0133"), ObdParser.parseDtcs("43 01 33 00 00 00 00"))
        assertEquals(listOf("P0133", "C0300"), ObdParser.parseDtcs("43 01 33 43 00 00 00"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("43 00 00 00 00 00 00"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("NO DATA"))
    }

    @Test
    fun dtcLettersFollowTheTopTwoBits() {
        assertEquals("P0133", ObdParser.decodeDtc(0x01, 0x33))
        assertEquals("C0300", ObdParser.decodeDtc(0x43, 0x00))
        assertEquals("B1234", ObdParser.decodeDtc(0x92, 0x34))
        assertEquals("U0123", ObdParser.decodeDtc(0xC1, 0x23))
    }
}
