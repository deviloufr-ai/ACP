package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The driver's correction to the OBD speed. */
class SpeedCorrectionTest {

    @Test
    fun theOffsetIsAddedToAMovingCar() {
        assertEquals(53, SpeedCorrection.correct(50, 3))
        assertEquals(48, SpeedCorrection.correct(50, -2))
        assertEquals(50, SpeedCorrection.correct(50, 0))
    }

    @Test
    fun aStoppedCarStaysAtZero() {
        assertEquals(0, SpeedCorrection.correct(0, 5))
        assertEquals(0, SpeedCorrection.correct(0, -5))
    }

    @Test
    fun theCorrectionNeverGoesBelowZero() {
        assertEquals(0, SpeedCorrection.correct(2, -5))
    }

    @Test
    fun theOffsetReadsWithItsSign() {
        assertEquals("+3 km/h", speedOffsetText(3))
        assertEquals("−2 km/h", speedOffsetText(-2))
        assertEquals("0 km/h", speedOffsetText(0))
    }
}
