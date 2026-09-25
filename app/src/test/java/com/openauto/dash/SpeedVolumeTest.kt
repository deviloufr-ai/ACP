package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** Volume that follows speed: how many notches for which speed. */
class SpeedVolumeTest {

    @Test
    fun offNeverAddsAnything() {
        assertEquals(0, SpeedVolume.boostFor(130, SpeedVolumeLevel.OFF))
        assertEquals(0, SpeedVolume.target(130, 0, SpeedVolumeLevel.OFF))
    }

    @Test
    fun townSpeedsStayAtTheDriversVolume() {
        assertEquals(0, SpeedVolume.boostFor(0, SpeedVolumeLevel.HIGH))
        assertEquals(0, SpeedVolume.boostFor(39, SpeedVolumeLevel.HIGH))
    }

    @Test
    fun notchesComeOnWithSpeedUpToTheLevelsMaximum() {
        assertEquals(1, SpeedVolume.boostFor(40, SpeedVolumeLevel.MEDIUM))
        assertEquals(2, SpeedVolume.boostFor(65, SpeedVolumeLevel.MEDIUM))
        assertEquals(3, SpeedVolume.boostFor(90, SpeedVolumeLevel.MEDIUM))
        assertEquals(4, SpeedVolume.boostFor(130, SpeedVolumeLevel.MEDIUM))
        assertEquals(4, SpeedVolume.boostFor(200, SpeedVolumeLevel.MEDIUM))
        assertEquals(2, SpeedVolume.boostFor(130, SpeedVolumeLevel.LOW))
        assertEquals(6, SpeedVolume.boostFor(130, SpeedVolumeLevel.HIGH))
    }

    @Test
    fun speedingUpRaisesAtOnce() {
        assertEquals(2, SpeedVolume.target(65, 1, SpeedVolumeLevel.MEDIUM))
    }

    @Test
    fun aNotchOnlyComesOffWellUnderItsThreshold() {
        // Notch 2 came on at 65 km/h: it stays down to 58, and goes at 56.
        assertEquals(2, SpeedVolume.target(60, 2, SpeedVolumeLevel.MEDIUM))
        assertEquals(2, SpeedVolume.target(58, 2, SpeedVolumeLevel.MEDIUM))
        assertEquals(1, SpeedVolume.target(56, 2, SpeedVolumeLevel.MEDIUM))
    }

    @Test
    fun stoppingGivesEverythingBack() {
        assertEquals(0, SpeedVolume.target(0, 4, SpeedVolumeLevel.MEDIUM))
    }

    @Test
    fun anUnknownSpeedHolds() {
        assertEquals(3, SpeedVolume.target(null, 3, SpeedVolumeLevel.MEDIUM))
    }

    @Test
    fun aWeakerLevelCapsWhatIsHeld() {
        assertEquals(2, SpeedVolume.target(null, 5, SpeedVolumeLevel.LOW))
        assertEquals(2, SpeedVolume.target(130, 5, SpeedVolumeLevel.LOW))
    }

    @Test
    fun aNotchIsAboutAFifteenthOfTheRange() {
        assertEquals(1, SpeedVolume.unitFor(15))
        assertEquals(1, SpeedVolume.unitFor(7))
        assertEquals(2, SpeedVolume.unitFor(30))
        assertEquals(3, SpeedVolume.unitFor(40))
        assertEquals(1, SpeedVolume.unitFor(0))
    }
}
