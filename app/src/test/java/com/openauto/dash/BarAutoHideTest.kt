package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** How long the bottom bar waits before hiding itself. */
class BarAutoHideTest {

    @Test
    fun waitsTheChosenSeconds() {
        assertEquals(0L, barHideDelayMs(0, afterReveal = false))
        assertEquals(5_000L, barHideDelayMs(5, afterReveal = false))
        assertEquals(20_000L, barHideDelayMs(MAX_BAR_HIDE_SECONDS, afterReveal = false))
    }

    @Test
    fun staysInRange() {
        assertEquals(0L, barHideDelayMs(-3, afterReveal = false))
        assertEquals(MAX_BAR_HIDE_SECONDS * 1_000L, barHideDelayMs(90, afterReveal = false))
    }

    @Test
    fun aSwipeUpLeavesTimeToReachTheBar() {
        assertEquals(BAR_REVEAL_GRACE_MS, barHideDelayMs(0, afterReveal = true))
        assertEquals(BAR_REVEAL_GRACE_MS, barHideDelayMs(1, afterReveal = true))
        assertEquals(12_000L, barHideDelayMs(12, afterReveal = true))
    }
}
