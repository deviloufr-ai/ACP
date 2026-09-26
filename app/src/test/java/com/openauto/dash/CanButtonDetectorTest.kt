package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun aValueHeldAsLongAsItWasStillIsNotAButton() {
        // A channel alternating between two messages: 2 s of one, 2 s of the other.
        val d = CanButtonDetector()
        d.onChange("65.04", null, "41 FD 04 4F 10 62", 0)
        var previous = "41 FD 04 4F 10 62"
        for (i in 1..10) {
            val hex = if (i % 2 == 1) "41 FD 04 4F 10 63" else "41 FD 04 4F 10 62"
            assertNull(d.onChange("65.04", previous, hex, i * 2_000L))
            previous = hex
        }
    }

    @Test
    fun aFrameThatKeepsFlippingIsNotAButton() {
        // Still 4 s, other value 1 s, back: looks like a press, until it has happened too often.
        val d = CanButtonDetector()
        d.onChange("65.04", null, "00", 0)
        var t = 0L
        repeat(4) {
            t += 4_000
            assertNull(d.onChange("65.04", "00", "01", t))
            t += 1_000
            assertEquals("01", d.onChange("65.04", "01", "00", t))
        }
        t += 4_000
        d.onChange("65.04", "00", "01", t)
        t += 1_000
        assertNull(d.onChange("65.04", "01", "00", t))
        // A quiet minute later it is trusted again.
        t += 61_000
        d.onChange("65.04", "00", "01", t)
        t += 300
        assertEquals("01", d.onChange("65.04", "01", "00", t))
    }

    @Test
    fun aSecondPressAfterReadingThePromptIsSeen() {
        val d = CanButtonDetector()
        d.onChange("32", null, "00 00", 0)
        d.onChange("32", "00 00", "02 01", 10_000)
        assertEquals("02 01", d.onChange("32", "02 01", "00 00", 10_300))
        d.onChange("32", "00 00", "02 01", 13_000)
        assertEquals("02 01", d.onChange("32", "02 01", "00 00", 13_250))
    }

    @Test
    fun onlyChangesAfterStillnessAreFlaggedQuiet() {
        val d = CanButtonDetector()
        d.onChange("32", null, "00 00", 0)
        assertFalse(d.lastWasQuiet)
        d.onChange("32", "00 00", "02 01", 10_000)
        assertTrue(d.lastWasQuiet)
        d.onChange("32", "02 01", "00 00", 10_300)
        assertFalse(d.lastWasQuiet)
    }
}
