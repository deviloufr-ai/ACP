package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Grid placement rules and the layout JSON, both pure Kotlin over [DashboardItem]. */
class DashboardStoreTest {

    private fun widget(kind: BuiltinKind, x: Int, y: Int, w: Int, h: Int) =
        DashboardItem.BuiltinWidget(kind, x, y, w, h)

    private fun noOverlaps(items: List<DashboardItem>) {
        for (i in items.indices) for (j in i + 1 until items.size) {
            assertFalse("${items[i]} overlaps ${items[j]}", items[i].overlaps(items[j]))
        }
    }

    @Before
    fun resetRetained() {
        // The store is a singleton; make sure no retained tiles leak between tests.
        DashboardStore.parsePages("[]")
    }

    // --- placement -----------------------------------------------------------

    @Test
    fun firstFreeCellScansRowByRow() {
        assertEquals(0 to 0, DashboardStore.firstFreeCell(emptyList(), 3, 2))
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertEquals(3 to 0, DashboardStore.firstFreeCell(page, 3, 2))
        // Nothing fits a full-width span next to it: goes to the next row.
        assertEquals(0 to 2, DashboardStore.firstFreeCell(page, GRID_COLS, 2))
    }

    @Test
    fun canPlaceRespectsBoundsAndOtherTiles() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, 1, 0, 3, 2))
        assertTrue(DashboardStore.canPlace(page, 0, 1, 0, 3, 2)) // ignoring itself
        assertTrue(DashboardStore.canPlace(page, null, 3, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, GRID_COLS - 2, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, -1, 0, 3, 2))
    }

    @Test
    fun moveToFreeCellJustMoves() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 3, 0, 3, 2))
        val result = DashboardStore.moveResolving(page, 0, 0, 4)
        assertNotNull(result)
        assertEquals(widget(BuiltinKind.CLOCK, 0, 4, 3, 2), result!![0])
        assertEquals(page[1], result[1])
    }

    @Test
    fun moveOntoSingleSameSizedTileSwaps() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 3, 0, 3, 2))
        val result = DashboardStore.moveResolving(page, 0, 3, 0)!!
        assertEquals(widget(BuiltinKind.CLOCK, 3, 0, 3, 2), result[0])
        assertEquals(widget(BuiltinKind.AUDIO, 0, 0, 3, 2), result[1])
    }

    @Test
    fun moveOntoSeveralTilesNudgesThemAside() {
        val page = listOf(
            widget(BuiltinKind.CLOCK, 0, 0, 3, 2),
            widget(BuiltinKind.AUDIO, 3, 0, 3, 2),
            widget(BuiltinKind.WEATHER, 6, 0, 3, 2)
        )
        val result = DashboardStore.moveResolving(page, 0, 4, 0)
        assertNotNull(result)
        assertEquals(4, result!![0].x)
        assertEquals(0, result[0].y)
        assertEquals(3, result.size)
        noOverlaps(result)
    }

    @Test
    fun moveOutOfBoundsIsRefused() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertNull(DashboardStore.moveResolving(page, 0, GRID_COLS - 1, 0))
        assertNull(DashboardStore.moveResolving(page, 5, 0, 0))
    }

    @Test
    fun nearestFreeCellPrefersTheClosestSpot() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertEquals(3 to 0, DashboardStore.nearestFreeCell(page, 3, 2, 2, 0))
        assertEquals(0 to 3, DashboardStore.nearestFreeCell(page, 3, 2, 0, 3)) // free right there
        assertEquals(0 to 2, DashboardStore.nearestFreeCell(page, 3, 2, 0, 1)) // one row down beats four across
        assertNull(DashboardStore.nearestFreeCell(page, GRID_COLS, GRID_ROWS, 0, 0))
    }

    @Test
    fun repairOverlapsKeepsTheFirstAndRelocatesTheRest() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 0, 0, 3, 2))
        val fixed = DashboardStore.repairOverlaps(page)
        assertEquals(2, fixed.size)
        assertEquals(page[0], fixed[0])
        noOverlaps(fixed)
    }

    // --- JSON ----------------------------------------------------------------

    @Test
    fun layoutRoundTripsThroughJson() {
        val pages = listOf(
            listOf(
                widget(BuiltinKind.NAVMAP, 0, 0, 5, 3),
                DashboardItem.AppShortcut("com.example.a", 5, 0, 2, 2),
                DashboardItem.SplitPair("com.example.a", "com.example.b", 7, 0, 2, 2),
                DashboardItem.LaunchBar(listOf("com.example.a", "com.example.b"), 0, 3, 8, 1),
                DashboardItem.SystemWidget(42, 0, 4, 5, 3)
            ),
            emptyList(),
            listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        )
        val json = DashboardStore.serializePages(pages)
        assertTrue(json.startsWith("{"))
        assertEquals(pages, DashboardStore.parsePages(json))
    }

    @Test
    fun legacyBareArrayStillLoads() {
        val legacy = """[[{"t":"builtin","k":"CLOCK","gx":1,"gy":2,"gw":3,"gh":2}],[],[]]"""
        val pages = DashboardStore.parsePages(legacy)!!
        assertEquals(3, pages.size)
        assertEquals(listOf(widget(BuiltinKind.CLOCK, 1, 2, 3, 2)), pages[0])
    }

    @Test
    fun legacyTileWithoutCoordinatesGetsItsKindDefaultSize() {
        val pages = DashboardStore.parsePages("""[[{"t":"builtin","k":"CLOCK"}]]""")!!
        val clock = pages[0].single()
        assertEquals(-1, clock.x) // unplaced sentinel: load() flows it in
        assertEquals(BuiltinKind.CLOCK.defaultW, clock.w)
        assertEquals(BuiltinKind.CLOCK.defaultH, clock.h)
    }

    @Test
    fun unknownTilesAreCarriedThroughToTheNextSave() {
        val fromNewerBuild = """{"v":9,"pages":[[
            {"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2},
            {"t":"builtin","k":"WARP_DRIVE","gx":3,"gy":0,"gw":3,"gh":2},
            {"t":"app","pkg":"com.example.a","gx":6,"gy":0,"gw":2,"gh":2}
        ]]}"""
        val pages = DashboardStore.parsePages(fromNewerBuild)!!
        assertEquals(listOf(DashboardItem.AppShortcut("com.example.a", 6, 0, 2, 2)), pages[0])

        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { pages.getOrElse(it) { emptyList() } })
        assertTrue(saved.contains("hologram"))
        assertTrue(saved.contains("WARP_DRIVE"))
        assertTrue(saved.contains("com.example.a"))
    }

    @Test
    fun garbageIsRejectedNotDefaulted() {
        assertNull(DashboardStore.parsePages("not json at all"))
        assertNull(DashboardStore.parsePages("{"))
    }
}
