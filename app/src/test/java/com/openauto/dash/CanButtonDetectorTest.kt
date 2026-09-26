package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Steering wheel buttons in the CAN stream: pressed then let go, told apart from moving values. */
class CanButtonDetectorTest {

    @Test
    fun pressThenReleaseIsAButton() {
        val d = CanButtonDetector()
        assertNull(d.onChange("32", null, "00 00", 0))
        assertNull(d.onChange("32", "00 00", "02 01", 10_000))
        assertEquals("02 01", d.onChange("32", "02 01", "00 00", 10_300))
    }

    @Test
    fun aHeldButtonStillCountsWhenLetGoInTime() {
        val d = CanButtonDetector()
        d.onChange("32", null, "00 00", 0)
        d.onChange("32", "00 00", "05 01", 10_000)
        assertEquals("05 01", d.onChange("32", "05 01", "00 00", 14_000))
    }

    @Test
    fun aFrameSeenForTheFirstTimeIsNotAPress() {
        val d = CanButtonDetector()
        assertNull(d.onChange("32", null, "02 01", 0))
        assertNull(d.onChange("32", "02 01", "00 00", 300))
    }

    @Test
    fun aValueThatKeepsChangingIsNotAButton() {
        val d = CanButtonDetector()
        var previous: String? = null
        for (i in 0 until 20) {
            val hex = if (i % 2 == 0) "0A 10" else "0A 11"
            assertNull(d.onChange("65.05", previous, hex, i * 200L))
            previous = hex
        }
    }

    @Test
    fun aValueThatDriftsAndNeverComesBackIsNotAButton() {
        val d = CanButtonDetector()
        d.onChange("44", null, "30", 0)
        assertNull(d.onChange("44", "30", "31", 10_000))
        assertNull(d.onChange("44", "31", "32", 20_000))
    }

    @Test
    fun aReleaseTooLateIsNotAPress() {
        val d = CanButtonDetector()
        d.onChange("32", null, "00 00", 0)
        d.onChange("32", "00 00", "02 01", 10_000)
        assertNull(d.onChange("32", "02 01", "00 00", 16_000))
    }

    @Test
    fun otherFramesChangingMeanwhileDoNotSpoilThePress() {
        val d = CanButtonDetector()
        d.onChange("32", null, "00 00", 0)
        d.onChange("20", null, "10", 0)
        d.onChange("32", "00 00", "02 01", 10_000)
        d.onChange("20", "10", "11", 10_100)
        assertEquals("02 01", d.onChange("32", "02 01", "00 00", 10_300))
    }

    @Test
    fun resetForgetsAHalfSeenPress() {
        val d = CanButtonDetector()
        d.onChange("32", null, "00 00", 0)
        d.onChange("32", "00 00", "02 01", 10_000)
        d.reset()
        assertNull(d.onChange("32", "02 01", "00 00", 10_300))
    }
}
