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
        val seen = DockPolicy.Memory(hadWindow = true, lastStack = 7)
        val (step, mem) = DockPolicy.onPresent(seen, window(tile), tile, area, lastRaiseAt = 0, now = 100_000)
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
        // Centred on the tile but taller than it, poking above the area. First it
        // is asked to take the tile's size; still that big afterwards, it is at the
        // app's minimum: move it down, keep the size, report oversize.
        val big = window(ScreenRect(400, 50, 900, 450))
        val (first, mem) = DockPolicy.onPresent(DockPolicy.Memory(), big, tile, area, 0, 100_000)
        assertEquals(tile, (first as DockPolicy.Step.Keep).place)
        assertNull(first.oversizePx)
        val (step, _) = DockPolicy.onPresent(mem, big, tile, area, 0, 102_500)
        val keep = step as DockPolicy.Step.Keep
        assertEquals(ScreenRect(400, 80, 900, 480), keep.place)
        assertEquals(500 to 400, keep.oversizePx)
        assertFalse(keep.docked)
    }

    @Test
    fun windowLeftAtAnOlderTallerTileSizeIsShrunkToTheTile() {
        // The tile moved down and got shorter when the status bar came up, but the
        // window kept the old tile's size and now runs past the area's bottom (over
        // the launcher bar). It must take the tile's size, not be taken for the
        // app's minimum: no oversize report, or the tile would grow to match.
        val stale = window(ScreenRect(400, 60, 900, 700))
        val (step, mem) = DockPolicy.onPresent(DockPolicy.Memory(), stale, tile, area, 0, 100_000)
        val keep = step as DockPolicy.Step.Keep
        assertFalse(keep.docked)
        assertEquals(tile, keep.place)
        assertNull(keep.oversizePx)
        assertEquals(tile, mem.askedFor)
    }

    @Test
    fun windowOfAnotherSizeInsideTheAreaIsResizedNotAcceptedAsDocked() {
        // Still within the dashboard area, but taller than the tile (an older size)
        // or shorter (the tile was enlarged): both are resized to the tile.
        for (bounds in listOf(ScreenRect(400, 100, 900, 460), ScreenRect(400, 120, 900, 360))) {
            val (step, _) = DockPolicy.onPresent(DockPolicy.Memory(), window(bounds), tile, area, 0, 100_000)
            val keep = step as DockPolicy.Step.Keep
            assertFalse(keep.docked)
            assertEquals(tile, keep.place)
            assertNull(keep.oversizePx)
        }
    }

    @Test
    fun theAppsMinimumSizeIsAcceptedOnceTheTileSizeWasAskedFor() {
        // Asked for the tile's size, the system kept the window taller: that is the
        // app's minimum. It fits the area, so it counts as docked and the tile grows.
        val min = window(ScreenRect(400, 100, 900, 460))
        val asked = DockPolicy.Memory(hadWindow = true, attempts = 1, lastStack = 7, askedFor = tile)
        val (step, _) = DockPolicy.onPresent(asked, min, tile, area, 0, 100_000)
        val keep = step as DockPolicy.Step.Keep
        assertTrue(keep.docked)
        assertNull(keep.place)
        assertEquals(500 to 360, keep.oversizePx)
        // A refused resize proves nothing about the minimum: ask again.
        val (retry, _) = DockPolicy.onPresent(asked.copy(lastPlacementFailed = true), min, tile, area, 0, 100_000)
        assertEquals(tile, (retry as DockPolicy.Step.Keep).place)
    }

    @Test
    fun aTileThatMovedIsAskedForAgain() {
        // The size accepted for one tile rectangle says nothing about a new one.
        val min = window(ScreenRect(400, 100, 900, 460))
        val asked = DockPolicy.Memory(hadWindow = true, lastStack = 7, askedFor = tile)
        val moved = ScreenRect(400, 140, 900, 420)
        val (step, _) = DockPolicy.onPresent(asked, min, moved, area, 0, 100_000)
        assertEquals(moved, (step as DockPolicy.Step.Keep).place)
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

    @Test
    fun aWindowListedInFrontIsNeverRaised() {
        // Raising a freeform task on this head unit can turn it fullscreen, so
        // it is only done when the listing says the dashboard covers the window:
        // not when it first appears, nor when it comes back from the edge.
        val (first, _) = DockPolicy.onPresent(DockPolicy.Memory(), window(tile), tile, area, lastRaiseAt = 0, now = 100_000)
        assertFalse((first as DockPolicy.Step.Keep).raise)
        val seen = DockPolicy.Memory(hadWindow = true, lastStack = 7)
        val (back, _) = DockPolicy.onPresent(seen, window(ScreenRect(1276, 100, 1776, 400)), tile, area, lastRaiseAt = 0, now = 100_000)
        assertFalse((back as DockPolicy.Step.Keep).raise)
        assertEquals(tile, back.place)
        val placed = DockPolicy.Memory(hadWindow = true, lastStack = 7, attempts = 1, askedFor = tile)
        val (settled, _) = DockPolicy.onPresent(placed, window(tile), tile, area, lastRaiseAt = 0, now = 102_500)
        assertTrue((settled as DockPolicy.Step.Keep).docked)
        assertFalse(settled.raise)
    }
}
