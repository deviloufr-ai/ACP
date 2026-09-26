package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The head unit radio's station as the media tile shows it. */
class HeadUnitMediaTest {

    @Test
    fun fmFrequencies() {
        assertEquals("87.5 MHz", radioLabel(HeadUnitMedia.RadioStation(8750, 0, null)))
        assertEquals("98.55 MHz", radioLabel(HeadUnitMedia.RadioStation(9855, 1, null)))
        assertEquals("104 MHz", radioLabel(HeadUnitMedia.RadioStation(10400, 2, null)))
    }

    @Test
    fun amFrequencies() {
        assertEquals("1008 kHz", radioLabel(HeadUnitMedia.RadioStation(1008, 3, null)))
    }

    @Test
    fun bands() {
        assertEquals("FM1", bandLabel(0))
        assertEquals("FM3", bandLabel(2))
        assertEquals("AM1", bandLabel(3))
        assertEquals("AM3", bandLabel(5))
        assertNull(bandLabel(9))
    }

    @Test
    fun stockPlayers() {
        assertTrue(HeadUnitMedia.isStock("com.qf.bluetooth"))
        assertTrue(HeadUnitMedia.isStock("com.android.fmradio.ext"))
        assertFalse(HeadUnitMedia.isStock("com.spotify.music"))
        assertFalse(HeadUnitMedia.isStock(null))
    }
}
