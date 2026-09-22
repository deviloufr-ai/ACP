package com.openauto.dash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The kinds of built-in (app-provided) widgets a dashboard tile can show. These
 * are rendered by our own Compose panels, not by the Android app-widget host.
 */
enum class BuiltinKind(val label: String) {
    NAVMAP("Map (MapLibre)"),
    MEDIA("Music player"),
    TELEMETRY("OBD telemetry"),
    OBD_DTC("OBD fault codes"),
    OBD_ALL("OBD all data"),
    RANGE("Fuel & range"),
    CAR3D("3D car"),
    DOORS("Doors"),
    CAN_MON("CAN monitor (debug)")
}

/**
 * One tile on a dashboard page, placed freely on a [GRID_COLS] x [GRID_ROWS]
 * grid. [x],[y] are the top-left cell (0-based) and [w],[h] are the span in
 * cells. Tiles may be moved and resized to any cell rectangle that fits.
 *
 *  - [AppShortcut]  a small icon that launches an installed app,
 *  - [SplitPair]    launches two apps side-by-side in split-screen,
 *  - [BuiltinWidget] one of our own cards (Maps / media / OBD),
 *  - [SystemWidget]  a real Android app-widget, hosted via [WidgetHostHolder].
 */
sealed interface DashboardItem {
    val x: Int
    val y: Int
    val w: Int
    val h: Int

    data class AppShortcut(
        val packageName: String,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 2, override val h: Int = 2
    ) : DashboardItem

    data class SplitPair(
        val primaryPackage: String,
        val secondaryPackage: String,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 2, override val h: Int = 2
    ) : DashboardItem

    data class BuiltinWidget(
        val kind: BuiltinKind,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 5, override val h: Int = 3
    ) : DashboardItem

    data class SystemWidget(
        val appWidgetId: Int,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 5, override val h: Int = 3
    ) : DashboardItem
}

/** The dashboard grid: 12 cells across, 7 down. */
const val GRID_COLS = 12
const val GRID_ROWS = 7

/** Compact icon tiles (shortcuts / split pairs) vs. larger widget cards. */
fun DashboardItem.isCompactTile(): Boolean =
    this is DashboardItem.AppShortcut || this is DashboardItem.SplitPair

/** Smallest span this tile may be resized to (icons stay small, widgets bigger). */
fun DashboardItem.minW(): Int = if (isCompactTile()) 1 else 3
fun DashboardItem.minH(): Int = if (isCompactTile()) 1 else 2

/** Returns a copy placed at cell [x],[y] spanning [w] x [h], clamped to the grid. */
fun DashboardItem.withCell(x: Int, y: Int, w: Int, h: Int): DashboardItem {
    val cw = w.coerceIn(minW(), GRID_COLS)
    val ch = h.coerceIn(minH(), GRID_ROWS)
    val cx = x.coerceIn(0, GRID_COLS - cw)
    val cy = y.coerceIn(0, GRID_ROWS - ch)
    return when (this) {
        is DashboardItem.AppShortcut -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.SplitPair -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.BuiltinWidget -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.SystemWidget -> copy(x = cx, y = cy, w = cw, h = ch)
    }
}

/**
 * Persists the 3 swipeable dashboards (each an ordered list of [DashboardItem])
 * to SharedPreferences as JSON. The layout is the user's, so it survives restarts.
 */
object DashboardStore {

    const val PAGE_COUNT = 3

    private const val PREFS = "dashboard_layout_prefs"
    private const val KEY_PAGES = "pages"

    /** Default layout when nothing is saved yet: Maps + music on page 1. */
    private fun defaultPages(): List<List<DashboardItem>> = listOf(
        autoPlace(
            listOf(
                DashboardItem.BuiltinWidget(BuiltinKind.NAVMAP),
                DashboardItem.BuiltinWidget(BuiltinKind.MEDIA)
            )
        ),
        emptyList(),
        emptyList()
    )

    fun load(context: Context): List<List<DashboardItem>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAGES, null) ?: return defaultPages()

        val parsed = runCatching {
            val pages = JSONArray(raw)
            (0 until pages.length()).map { p ->
                val page = pages.optJSONArray(p) ?: JSONArray()
                (0 until page.length()).mapNotNull { i -> page.optJSONObject(i)?.toItem() }
            }
        }.getOrNull() ?: return defaultPages()

        // Always return exactly PAGE_COUNT pages; auto-place any page whose tiles
        // predate grid coordinates (migrated from the old column layout).
        return List(PAGE_COUNT) { p ->
            val page = parsed.getOrElse(p) { emptyList() }
            if (page.any { it.x < 0 }) autoPlace(page) else page
        }
    }

    fun save(context: Context, pages: List<List<DashboardItem>>) {
        val json = JSONArray()
        pages.take(PAGE_COUNT).forEach { page ->
            val arr = JSONArray()
            page.forEach { arr.put(it.toJson()) }
            json.put(arr)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PAGES, json.toString()).apply()
    }

    /** First cell where a [w] x [h] tile fits without overlapping [occupied]. */
    fun firstFreeCell(items: List<DashboardItem>, w: Int, h: Int): Pair<Int, Int>? {
        val occ = occupancy(items)
        return firstFree(occ, w.coerceIn(1, GRID_COLS), h.coerceIn(1, GRID_ROWS))
    }

    /**
     * True when a proposed tile rectangle is fully in bounds and does not touch
     * another tile. [ignoredIndex] is the tile currently being moved or resized.
     * Keeping this rule in the model makes add, drag, and resize use identical
     * collision behaviour.
     */
    fun canPlace(
        items: List<DashboardItem>,
        ignoredIndex: Int?,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ): Boolean {
        if (x < 0 || y < 0 || w < 1 || h < 1 || x + w > GRID_COLS || y + h > GRID_ROWS) {
            return false
        }
        return items.withIndex().none { (index, item) ->
            index != ignoredIndex && rectanglesOverlap(x, y, w, h, item.x, item.y, item.w, item.h)
        }
    }

    private fun occupancy(items: List<DashboardItem>): Array<BooleanArray> {
        val occ = Array(GRID_ROWS) { BooleanArray(GRID_COLS) }
        items.forEach { mark(occ, it.x, it.y, it.w, it.h, true) }
        return occ
    }

    private fun firstFree(occ: Array<BooleanArray>, w: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..GRID_ROWS - h) {
            for (x in 0..GRID_COLS - w) {
                if (fits(occ, x, y, w, h)) return x to y
            }
        }
        return null
    }

    private fun fits(occ: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int): Boolean {
        for (yy in y until y + h) for (xx in x until x + w) {
            if (yy !in 0 until GRID_ROWS || xx !in 0 until GRID_COLS || occ[yy][xx]) return false
        }
        return true
    }

    private fun mark(occ: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int, v: Boolean) {
        for (yy in y until y + h) for (xx in x until x + w) {
            if (yy in 0 until GRID_ROWS && xx in 0 until GRID_COLS) occ[yy][xx] = v
        }
    }

    private fun rectanglesOverlap(
        ax: Int, ay: Int, aw: Int, ah: Int,
        bx: Int, by: Int, bw: Int, bh: Int
    ): Boolean = ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by

    /** Flow tiles onto the grid in order, first free cell for each (migration). */
    private fun autoPlace(items: List<DashboardItem>): List<DashboardItem> {
        val occ = Array(GRID_ROWS) { BooleanArray(GRID_COLS) }
        return items.map { item ->
            val w = item.w.coerceIn(item.minW(), GRID_COLS)
            val h = item.h.coerceIn(item.minH(), GRID_ROWS)
            val pos = firstFree(occ, w, h) ?: (0 to 0)
            mark(occ, pos.first, pos.second, w, h, true)
            item.withCell(pos.first, pos.second, w, h)
        }
    }

    private fun DashboardItem.toJson(): JSONObject {
        val o = when (this) {
            is DashboardItem.AppShortcut -> JSONObject().put("t", "app").put("pkg", packageName)
            is DashboardItem.SplitPair ->
                JSONObject().put("t", "split").put("a", primaryPackage).put("b", secondaryPackage)
            is DashboardItem.BuiltinWidget -> JSONObject().put("t", "builtin").put("k", kind.name)
            is DashboardItem.SystemWidget -> JSONObject().put("t", "widget").put("id", appWidgetId)
        }
        return o.put("gx", x).put("gy", y).put("gw", w).put("gh", h)
    }

    /** Copy with x = -1, the sentinel load() uses to auto-place migrated tiles. */
    private fun DashboardItem.markUnplaced(): DashboardItem = when (this) {
        is DashboardItem.AppShortcut -> copy(x = -1)
        is DashboardItem.SplitPair -> copy(x = -1)
        is DashboardItem.BuiltinWidget -> copy(x = -1)
        is DashboardItem.SystemWidget -> copy(x = -1)
    }

    private fun JSONObject.toItem(): DashboardItem? {
        val gx = optInt("gx", -1)
        val gy = optInt("gy", -1)
        val gw = optInt("gw", -1)
        val gh = optInt("gh", -1)
        fun place(item: DashboardItem): DashboardItem =
            if (gx >= 0 && gy >= 0 && gw > 0 && gh > 0) item.withCell(gx, gy, gw, gh)
            else item.markUnplaced()

        return when (optString("t")) {
            "app" -> optString("pkg").takeIf { it.isNotBlank() }
                ?.let { place(DashboardItem.AppShortcut(it)) }
            "split" -> {
                val a = optString("a"); val b = optString("b")
                if (a.isNotBlank() && b.isNotBlank()) place(DashboardItem.SplitPair(a, b)) else null
            }
            "builtin" -> runCatching { BuiltinKind.valueOf(optString("k")) }.getOrNull()
                ?.let { place(DashboardItem.BuiltinWidget(it)) }
            "widget" -> optInt("id", -1).takeIf { it != -1 }
                ?.let { place(DashboardItem.SystemWidget(it)) }
            else -> null
        }
    }
}
