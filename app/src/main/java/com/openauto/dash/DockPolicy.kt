package com.openauto.dash

/**
 * The tracking loop's decisions, one poll at a time, as pure functions of what
 * it sees and what it remembers. [PipAnchor.track] only carries out the
 * resulting [Step]; every rule about reopening, giving up, raising and placing
 * a window lives here, where it can be unit-tested without a device.
 */
object DockPolicy {

    /** Minimum gap between two launches of the same app. */
    const val REOPEN_COOLDOWN_MS = 15_000L

    /** A window listed behind the dashboard is raised at most this often, so a wrong listing can't cause focus flicker. */
    const val RAISE_COOLDOWN_MS = 8_000L

    /** Placement attempts per window before the tile stops fighting the system. */
    const val MAX_ATTEMPTS = 6

    /** Launches without a window ever appearing before auto-open is switched off (or Home would be trapped). */
    const val MAX_OPEN_ATTEMPTS = 2

    /** After this many refused placements the tile tries dragging the window instead. */
    const val SWIPE_AFTER_ATTEMPTS = 3

    /** A window this much larger than its tile is reported as oversize so the tile can grow. */
    const val OVERSIZE_RATIO = 1.08f

    /** Window and tile sizes this close, in pixels, count as the same size. */
    const val SIZE_SLACK_PX = 8

    /** Tries to bring a window back from the hidden display before that display is given up on. */
    const val MAX_UNHIDE_ATTEMPTS = 3

    /** What the loop remembers between polls for one app. */
    data class Memory(
        val hadWindow: Boolean = false,
        val openAttempts: Int = 0,
        val attempts: Int = 0,
        val lastStack: Int? = null,
        val lastPlacementFailed: Boolean = false,
        /** The tile rectangle the window was last asked to take (tells a stale size from the app's minimum). */
        val askedFor: ScreenRect? = null,
        /** Failed tries so far to bring the window back from the hidden display. */
        val unhideAttempts: Int = 0
    )

    sealed class Step {
        /** Nothing to do this poll. */
        object Idle : Step()
        /** The window vanished and we did not close it: stop keeping it open. */
        object UserClosed : Step()
        /** Launched repeatedly and never became a window: stop keeping it open. */
        object GiveUp : Step()
        /** Launch the app as a window at the tile ([attempt] is 1-based). */
        data class Reopen(val attempt: Int) : Step()
        /** The window is parked on the hidden display: move it back onto the screen first ([attempt] is 1-based). */
        data class Unhide(val attempt: Int) : Step()
        /** The window will not come back from the hidden display: let that display go (the window closes and is reopened). */
        object ReleaseHidden : Step()
        /**
         * The window exists. [raise] brings it above the dashboard; [place] is
         * where to move/resize it (null when it sits well), by dragging when
         * [swipe] is set; [gaveUp] and [oversizePx] feed the tile's status.
         */
        data class Keep(
            val docked: Boolean,
            val raise: Boolean,
            val place: ScreenRect?,
            val swipe: Boolean,
            val gaveUp: Boolean,
            val oversizePx: Pair<Int, Int>?
        ) : Step()
    }

    /**
     * No window for this app right now. [expectedGone] is true when one of our
     * own close paths removed it; [autoOpen] is the persisted keep-open intent;
     * [lastReopenAt] is when we last launched it (0 if never).
     */
    fun onMissing(mem: Memory, expectedGone: Boolean, autoOpen: Boolean, lastReopenAt: Long, now: Long): Pair<Step, Memory> {
        val next = mem.copy(hadWindow = false, attempts = 0, lastStack = null)
        if (mem.hadWindow && !expectedGone) return Step.UserClosed to next.copy(openAttempts = 0)
        if (!autoOpen || now - lastReopenAt <= REOPEN_COOLDOWN_MS) return Step.Idle to next
        if (mem.openAttempts >= MAX_OPEN_ATTEMPTS) return Step.GiveUp to next.copy(openAttempts = 0)
        return Step.Reopen(mem.openAttempts + 1) to next.copy(openAttempts = mem.openAttempts + 1)
    }

    /**
     * The window exists at [win]; the tile wants it on [rect] inside [limit]
     * (the dashboard area, null if unknown). [lastRaiseAt] is when we last
     * raised it (0 if never).
     */
    fun onPresent(mem: Memory, win: FloatingWindow, rect: ScreenRect, limit: ScreenRect?, lastRaiseAt: Long, now: Long): Pair<Step, Memory> {
        if (win.offDisplay) {
            // Parked out of sight: nothing can be placed until it is back on the
            // screen. A window that will not come back is closed with its hidden
            // display and reopened by the usual path, rather than stay lost.
            val seen = mem.copy(hadWindow = true, openAttempts = 0, lastStack = win.stackId)
            if (mem.unhideAttempts >= MAX_UNHIDE_ATTEMPTS) return Step.ReleaseHidden to seen.copy(unhideAttempts = 0)
            return Step.Unhide(mem.unhideAttempts + 1) to seen.copy(unhideAttempts = mem.unhideAttempts + 1)
        }
        var attempts = if (win.stackId != mem.lastStack) 0 else mem.attempts
        val b = win.bounds
        val close = b != null && WindowListing.isClose(b, rect)
        val inside = b == null || WindowListing.withinArea(b, limit)
        // A window of another size may just still have an older tile's size (the
        // tile moved when the status bar came up, or was resized while arranging),
        // so it is first asked to take the tile's size. Only a window that keeps
        // its own size after that is at the app's minimum, and is accepted.
        val sizeSettled = b == null || sameSize(b, rect) || (mem.askedFor == rect && !mem.lastPlacementFailed)
        val docked = close && inside && sizeSettled
        if (docked) attempts = 0

        // Raised only when the listing says the dashboard covers it, never on a
        // hunch: on this head unit bringing a freeform task to the front can
        // leave its window blank or turn it into a fullscreen one, which then
        // opens over the dashboard while the tile reopens another window.
        val raise = win.mode == "freeform" && (!win.visible || win.behindDashboard) && now - lastRaiseAt > RAISE_COOLDOWN_MS

        val oversize = b?.takeIf {
            sizeSettled && ((it.right - it.left) > (rect.right - rect.left) * OVERSIZE_RATIO ||
                (it.bottom - it.top) > (rect.bottom - rect.top) * OVERSIZE_RATIO)
        }?.let { (it.right - it.left) to (it.bottom - it.top) }

        var place: ScreenRect? = null
        var swipe = false
        if (!docked && attempts < MAX_ATTEMPTS) {
            attempts++
            // A drag cannot resize and its first touch expands a PiP, so it is
            // only worth trying once the resize commands were refused.
            swipe = attempts >= SWIPE_AFTER_ATTEMPTS && b != null && mem.lastPlacementFailed
            // The system kept the window at its minimum size, larger than the tile:
            // keep that size but move it back inside the dashboard area.
            place = if (close && !inside && sizeSettled && b != null && limit != null) WindowListing.keepInside(b, limit) else rect
        }
        val step = Step.Keep(docked, raise, place, swipe, gaveUp = attempts >= MAX_ATTEMPTS, oversizePx = oversize)
        return step to mem.copy(
            hadWindow = true, openAttempts = 0, attempts = attempts, lastStack = win.stackId,
            askedFor = if (place == rect) rect else mem.askedFor, unhideAttempts = 0
        )
    }

    private fun sameSize(a: ScreenRect, b: ScreenRect): Boolean =
        kotlin.math.abs((a.right - a.left) - (b.right - b.left)) <= SIZE_SLACK_PX &&
            kotlin.math.abs((a.bottom - a.top) - (b.bottom - b.top)) <= SIZE_SLACK_PX
}
