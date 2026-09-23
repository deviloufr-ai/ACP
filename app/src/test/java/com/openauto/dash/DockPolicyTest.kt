package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tracking loop's decisions: reopen, give up, raise, place. */
class DockPolicyTest {

    private val tile = ScreenRect(400, 100, 900, 400)
    private val area = ScreenRect(0, 80, 1280, 640)
    private fun window(bounds: ScreenRect?, mode: String = "freeform", visible: Boolean = true, behind: Boolean = false, stack: Int = 7) =
        FloatingWindow(stack, 63, "com.google.android.apps.maps", bounds, mode, visible, behind)

    // --- no window ------------------------------------------------------------

    @Test
    fun windowGoneWithoutUsIsTheUserClosingIt() {
        val (step, mem) = DockPolicy.onMissing(DockPolicy.Memory(hadWindow = true), expectedGone = false, autoOpen = true, lastReopenAt = 0, now = 100_000)
        assertEquals(DockPolicy.Step.UserClosed, step)
        assertFalse(mem.hadWindow)
    }

    @Test
    fun windowWeClosedIsReopenedAfterTheCooldown() {
        val mem0 = DockPolicy.Memory(hadWindow = true)
        val (step1, mem1) = DockPolicy.onMissing(mem0, expectedGone = true, autoOpen = true, lastReopenAt = 0, now = 100_000)
        assertEquals(DockPolicy.Step.Reopen(1), step1)
        // Launched just now: wait.
        val (step2, mem2) = DockPolicy.onMissing(mem1, expectedGone = false, autoOpen = true, lastReopenAt = 100_000, now = 102_500)
        assertEquals(DockPolicy.Step.Idle, step2)
        // Cooldown over, still nothing: second attempt, then give up.
        val (step3, mem3) = DockPolicy.onMissing(mem2, expectedGone = false, autoOpen = true, lastReopenAt = 100_000, now = 120_000)
        assertEquals(DockPolicy.Step.Reopen(2), step3)
        val (step4, mem4) = DockPolicy.onMissing(mem3, expectedGone = false, autoOpen = true, lastReopenAt = 120_000, now = 140_000)
        assertEquals(DockPolicy.Step.GiveUp, step4)
        assertEquals(0, mem4.openAttempts)
    }

    @Test
    fun noIntentMeansNothingHappens() {
        val (step, _) = DockPolicy.onMissing(DockPolicy.Memory(), expectedGone = false, autoOpen = false, lastReopenAt = 0, now = 100_000)
        assertEquals(DockPolicy.Step.Idle, step)
    }

    // --- window present ---------------------------------------------------------

    @Test
    fun dockedWindowNeedsNothing() {
        val (step, mem) = DockPolicy.onPresent(DockPolicy.Memory(), window(tile), tile, area, lastRaiseAt = 0, now = 100_000)
        val keep = step as DockPolicy.Step.Keep
        assertTrue(keep.docked)
        assertNull(keep.place)
        assertFalse(keep.raise)
        assertNull(keep.oversizePx)
        assertEquals(0, mem.attempts)
        assertTrue(mem.hadWindow)
    }

    @Test
    fun windowElsewhereIsPlacedOnTheTileUntilTheLimit() {
        var mem = DockPolicy.Memory()
        val parked = window(ScreenRect(960, 420, 1264, 608))
        repeat(DockPolicy.MAX_ATTEMPTS) { i ->
            val (step, next) = DockPolicy.onPresent(mem, parked, tile, area, 0, 100_000)
            val keep = step as DockPolicy.Step.Keep
            assertEquals(tile, keep.place)
            assertFalse(keep.swipe) // placements never failed
            mem = next
            assertEquals(i + 1, mem.attempts)
        }
        val (last, _) = DockPolicy.onPresent(mem, parked, tile, area, 0, 100_000)
        val keep = last as DockPolicy.Step.Keep
        assertNull(keep.place)
        assertTrue(keep.gaveUp)
    }

    @Test
    fun refusedPlacementsFallBackToADragAfterThreeTries() {
        val parked = window(ScreenRect(960, 420, 1264, 608))
        val mem = DockPolicy.Memory(attempts = 2, lastStack = 7, lastPlacementFailed = true)
        val (step, _) = DockPolicy.onPresent(mem, parked, tile, area, 0, 100_000)
        assertTrue((step as DockPolicy.Step.Keep).swipe)
    }

    @Test
    fun aNewStackResetsTheAttemptCount() {
        val parked = window(ScreenRect(960, 420, 1264, 608), stack = 9)
        val mem = DockPolicy.Memory(attempts = DockPolicy.MAX_ATTEMPTS, lastStack = 7)
        val (step, next) = DockPolicy.onPresent(mem, parked, tile, area, 0, 100_000)
        assertEquals(tile, (step as DockPolicy.Step.Keep).place)
        assertEquals(1, next.attempts)
    }

    @Test
    fun oversizedWindowIsKeptInsideTheAreaAtItsOwnSize() {
        // Centred on the tile but taller than it, poking above the area: move down, keep the size, report oversize.
        val big = window(ScreenRect(400, 50, 900, 450))
        val (step, _) = DockPolicy.onPresent(DockPolicy.Memory(), big, tile, area, 0, 100_000)
        val keep = step as DockPolicy.Step.Keep
        assertEquals(ScreenRect(400, 80, 900, 480), keep.place)
        assertEquals(500 to 400, keep.oversizePx)
        assertFalse(keep.docked)
    }

    @Test
    fun windowBehindTheDashboardIsRaisedButNotMoreThanOncePerCooldown() {
        val behind = window(tile, behind = true)
        val (step1, _) = DockPolicy.onPresent(DockPolicy.Memory(), behind, tile, area, lastRaiseAt = 0, now = 100_000)
        assertTrue((step1 as DockPolicy.Step.Keep).raise)
        val (step2, _) = DockPolicy.onPresent(DockPolicy.Memory(), behind, tile, area, lastRaiseAt = 100_000, now = 102_000)
        assertFalse((step2 as DockPolicy.Step.Keep).raise)
        // Picture-in-picture is managed by SystemUI; never raised by us.
        val pip = window(tile, mode = "pinned", behind = true)
        val (step3, _) = DockPolicy.onPresent(DockPolicy.Memory(), pip, tile, area, 0, 100_000)
        assertFalse((step3 as DockPolicy.Step.Keep).raise)
    }
}
