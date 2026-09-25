package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun olderProtocolsSendOneLinePerThreeCodes() {
        assertEquals(
            listOf("P0133", "P0134", "P0135", "P0136"),
            ObdParser.parseDtcs("43 01 33 01 34 01 35\r43 01 36 00 00 00 00")
        )
    }

    @Test
    fun canRepliesCarryACodeCountThatIsNotACode() {
        assertEquals(listOf("P0133"), ObdParser.parseDtcs("43 01 01 33"))
        assertEquals(listOf("P0133", "P2002"), ObdParser.parseDtcs("SEARCHING...\r43 02 01 33 20 02"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("43 00"))
    }

    @Test
    fun canMultiFrameRepliesAreJoined() {
        assertEquals(
            listOf("P0133", "P0134", "P0135", "P0136"),
            ObdParser.parseDtcs("00A\r0: 43 04 01 33 01 34\r1: 01 35 01 36 00 00 00")
        )
    }

    @Test
    fun engineLampAndStoredCountComeFromMonitorStatus() {
        assertEquals(EngineLamp(on = true, storedCodes = 3), ObdParser.parseEngineLamp("41 01 83 07 E5 00"))
        assertEquals(EngineLamp(on = false, storedCodes = 0), ObdParser.parseEngineLamp("SEARCHING...\r41 01 00 07 E5 00"))
        assertEquals(null, ObdParser.parseEngineLamp("NO DATA"))
    }

    @Test
    fun noAnswerIsNotTheSameAsNoFault() {
        assertEquals(null, ObdParser.parseDtcReply("NO DATA"))
        assertEquals(null, ObdParser.parseDtcReply("SEARCHING...\rSTOPPED"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcReply("43 00"))
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("43 00\r43 01 13 52"))
    }

    @Test
    fun pendingCodesComeFromMode07() {
        assertEquals(listOf("P0401"), ObdParser.parseDtcReply("47 01 04 01", mode = 0x47))
        assertEquals(null, ObdParser.parseDtcReply("43 01 04 01", mode = 0x47))
    }

    @Test
    fun theLampIsOnWhenAnyComputerSaysSo() {
        // Gearbox first (lamp off), engine second (lamp on, one code).
        assertEquals(EngineLamp(on = true, storedCodes = 1), ObdParser.parseEngineLamp("41 01 00 04 00 00\r41 01 81 07 65 04"))
    }

    @Test
    fun canProtocolsAreRecognisedFromDescribeProtocolNumber() {
        assertTrue(ObdParser.isCan11Bit("A6"))
        assertTrue(ObdParser.isCan11Bit("6"))
        assertTrue(ObdParser.isCan11Bit("8"))
        assertFalse(ObdParser.isCan11Bit("A7"))
        assertFalse(ObdParser.isCan11Bit("3"))
        assertFalse(ObdParser.isCan11Bit("?"))
    }

    // Replies captured from the C4 Picasso (2011, 1.6 HDi) on 2026-09-23: the adapter
    // puts the sender's address and the frame length in front, without spaces.
    @Test
    fun theC4PicassosRepliesWithHeadersAreRead() {
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("7E90243000000000000000\r7E80443011352FFFFFF"))
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("7E90247000000000000000\r7E80447011352FFFFFF", mode = 0x47))
        assertEquals(EngineLamp(on = true, storedCodes = 1), ObdParser.parseEngineLamp("7E8064101810EE000FF\r7E90641010004000000"))
    }

    @Test
    fun headersWithSpacesAnd29BitAddressesToo() {
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("7E8 04 43 01 13 52 FF FF FF"))
        assertEquals(listOf("P1352"), ObdParser.parseDtcReply("18DAF110 04 43 01 13 52 AA AA AA"))
        assertEquals(emptyList<String>(), ObdParser.parseDtcReply("7E9 02 43 00 00 00 00 00 00"))
    }

    @Test
    fun multiFrameRepliesWithHeadersAreJoinedPerSender() {
        assertEquals(
            listOf("P0133", "P0134", "P0135", "P0136"),
            ObdParser.parseDtcReply("7E8 10 0A 43 04 01 33 01 34\r7E9 02 43 00 00 00 00 00 00\r7E8 21 01 35 01 36 00 00 00")
        )
    }

    @Test
    fun everyAnsweringEcuIsRead() {
        assertEquals(listOf("P0133", "P0700"), ObdParser.parseDtcs("43 01 01 33\r43 01 07 00"))
    }

    @Test
    fun supportedPidBitmapIsReadMostSignificantFirst() {
        // BE 3E B8 11: 01 03-07, 0B-0F, 11 13-15, 1C 20.
        val pids = ObdParser.parseSupportedPids("41 00 BE 3E B8 11", 0x00)!!
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x20), pids)
        assertNull(ObdParser.parseSupportedPids("SEARCHING...\rUNABLE TO CONNECT", 0x00))
    }

    @Test
    fun everyAnsweringComputerAddsItsPids() {
        // Engine and gearbox answer on their own lines; with headers the sender comes first.
        val pids = ObdParser.parseSupportedPids("SEARCHING...\r7E8 06 41 00 80 00 00 00\r7E9 06 41 00 00 00 00 01", 0x00)!!
        assertEquals(setOf(0x01, 0x20), pids)
    }

    @Test
    fun supportedPidsFollowTheRanges() {
        val replies = mapOf(
            "0100" to "41 00 BE 3E B8 11",
            // 0120: only 0x21 and the next-range bit.
            "0120" to "41 20 80 00 00 01",
            // 0140: 0x42 (control-module voltage), no further range.
            "0140" to "41 40 40 00 00 00"
        )
        val asked = mutableListOf<String>()
        val s = ObdParser.supportedPids { asked += it; replies[it] }!!
        assertEquals(listOf("0100", "0120", "0140"), asked)
        assertTrue(s.has(0x0D))
        assertTrue(s.has(0x42))
        // The fuel level (012F) this car doesn't serve is skipped.
        assertFalse(s.has(0x2F))
        assertFalse(s.has(0x5C))
    }

    @Test
    fun anUnansweredRangeGivesItsPidsTheBenefitOfTheDoubt() {
        assertNull(ObdParser.supportedPids { "NO DATA" })
        // 0100 says a next range exists, but 0120 goes unanswered.
        val s = ObdParser.supportedPids { if (it == "0100") "41 00 BE 3E B8 11" else null }!!
        assertFalse(s.has(0x02))
        assertTrue(s.has(0x2F))
        assertTrue(s.has(0x42))
        // No next range: nothing past it is served.
        val short = ObdParser.supportedPids { if (it == "0100") "41 00 BE 3E B8 10" else null }!!
        assertFalse(short.has(0x42))
    }

    @Test
    fun aSilentEngineComputerReadsAsEngineOff() {
        val stopped = ObdData(speedKmh = 12, rpm = 820, coolantTempC = 88, throttlePct = 14, engineLoadPct = 22, voltage = 12.4).engineStopped()
        assertEquals(0, stopped.speedKmh)
        assertEquals(0, stopped.rpm)
        assertEquals(0, stopped.throttlePct)
        assertEquals(0, stopped.engineLoadPct)
        // What a gauge would still show stays.
        assertEquals(88, stopped.coolantTempC)
        assertEquals(12.4, stopped.voltage, 1e-9)
    }

    @Test
    fun dtcLettersFollowTheTopTwoBits() {
        assertEquals("P0133", ObdParser.decodeDtc(0x01, 0x33))
        assertEquals("C0300", ObdParser.decodeDtc(0x43, 0x00))
        assertEquals("B1234", ObdParser.decodeDtc(0x92, 0x34))
        assertEquals("U0123", ObdParser.decodeDtc(0xC1, 0x23))
    }
}
