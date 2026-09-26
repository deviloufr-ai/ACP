package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What switching on and off does: a briefing after a real stop, the parking spot from a fresh fix. */
class CarPowerTest {

    private val min = 60_000L

    @Test
    fun briefsAfterARealStopOnly() {
        val now = 1_000 * min
        assertTrue(briefOnIgnition(offAt = now - 45 * min, now = now))
        assertTrue(briefOnIgnition(offAt = now - CarStart.OFF_GAP_MS, now = now))
        // A fuel stop: no second briefing.
        assertFalse(briefOnIgnition(offAt = now - 4 * min, now = now))
        // Never seen switched off: the heartbeat decides.
        assertFalse(briefOnIgnition(offAt = null, now = now))
    }

    @Test
    fun parksOnAFreshFixOnly() {
        val now = 1_000 * min
        assertTrue(parkFixUsable(fixTime = now - 20_000, now = now))
        assertFalse(parkFixUsable(fixTime = now - 10 * min, now = now))
        assertFalse(parkFixUsable(fixTime = 0, now = now))
        // A fix stamped in the future (clock change) isn't trusted.
        assertFalse(parkFixUsable(fixTime = now + 5 * min, now = now))
    }
}
