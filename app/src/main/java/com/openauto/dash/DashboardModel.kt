package com.openauto.dash

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Sections of the "Add widget" catalogue. */
enum class WidgetCategory(val title: String) {
    DRIVING("DRIVING"),
    NAVIGATION("NAVIGATION"),
    VEHICLE("VEHICLE"),
    INFO("INFO & COMMS"),
    APPS("MEDIA & APPS")
}

/**
 * The kinds of built-in (app-provided) widgets a dashboard tile can show. These
 * are rendered by our own Compose panels, not by the Android app-widget host.
 * [defaultW] x [defaultH] is the span a fresh tile gets. Names are persisted,
 * so never rename an entry.
 */
enum class BuiltinKind(
    val label: String,
    val category: WidgetCategory,
    val blurb: String,
    val defaultW: Int = 5,
    val defaultH: Int = 3
) {
    NAVMAP("Map", WidgetCategory.NAVIGATION, "Free 3D map with search and routing"),
    NAVIGATION("Directions", WidgetCategory.NAVIGATION, "Next turn from Google Maps / Waze", 4, 3),
    PIP_ANCHOR("Maps window", WidgetCategory.NAVIGATION, "Docks the floating Maps window here", 4, 3),
    MEDIA("Music player", WidgetCategory.APPS, "Now playing with controls"),
    TELEMETRY("Telemetry", WidgetCategory.VEHICLE, "Speed gauge, revs, coolant, load, battery"),
    OBD_DTC("Fault codes", WidgetCategory.VEHICLE, "Read and clear OBD trouble codes", 3, 2),
    OBD_ALL("All OBD data", WidgetCategory.VEHICLE, "Every live OBD value"),
    RANGE("Fuel & range", WidgetCategory.VEHICLE, "Tank level and km to empty", 3, 3),
    CAR3D("3D car", WidgetCategory.VEHICLE, "Spin the car model", 4, 3),
    DOORS("Doors", WidgetCategory.VEHICLE, "Door and boot status from the MCU", 3, 2),
    CAN_MON("CAN monitor", WidgetCategory.VEHICLE, "Raw CAN frames (debug)", 4, 3),
    SPEED_HUD("Speed", WidgetCategory.DRIVING, "Big digital speed from OBD or GPS", 3, 2),
    COMPASS("Compass", WidgetCategory.DRIVING, "Heading, altitude and GPS speed", 3, 3),
    TRIP("Trip computer", WidgetCategory.DRIVING, "Distance, time, average and top speed", 4, 2),
    GFORCE("G-force", WidgetCategory.DRIVING, "Cornering and braking g", 4, 2),
    PARKING("Parking spot", WidgetCategory.NAVIGATION, "Save where you parked, walk back to it", 4, 2),
    CLOCK("Clock", WidgetCategory.INFO, "Time and date, large", 3, 2),
    WEATHER("Weather", WidgetCategory.INFO, "Conditions at the car (no account needed)", 4, 2),
    CALENDAR("Agenda", WidgetCategory.INFO, "Your next calendar events", 4, 3),
    QUICK_DIAL("Quick dial", WidgetCategory.INFO, "Starred contacts, one tap to call", 4, 2),
    NOTIFICATIONS("Notifications", WidgetCategory.INFO, "Messages and alerts from your apps", 4, 3),
    AUDIO("Audio", WidgetCategory.APPS, "Volume, mute, sound and Bluetooth settings", 3, 2)
}

/**
 * One tile on a dashboard page, placed freely on a [GRID_COLS] x [GRID_ROWS]
 * grid. [x],[y] are the top-left cell (0-based) and [w],[h] are the span in
 * cells. Tiles may be moved and resized to any cell rectangle that fits.
 *
 *  - [AppShortcut]   a small icon that launches an installed app,
 *  - [SplitPair]     launches two apps side-by-side in split-screen,
 *  - [LaunchBar]     an editable row of app icons (a dock),
 *  - [BuiltinWidget] one of our own cards (map / media / OBD / directions),
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

    data class LaunchBar(
        val packages: List<String> = emptyList(),
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 8, override val h: Int = 1
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
fun DashboardItem.minW(): Int = when {
    isCompactTile() -> 1
    this is DashboardItem.LaunchBar -> 3
    else -> 3
}

fun DashboardItem.minH(): Int = when {
    isCompactTile() -> 1
    this is DashboardItem.LaunchBar -> 1
    else -> 2
}

/** Returns a copy placed at cell [x],[y] spanning [w] x [h], clamped to the grid. */
fun DashboardItem.withCell(x: Int, y: Int, w: Int, h: Int): DashboardItem {
    val cw = w.coerceIn(minW(), GRID_COLS)
    val ch = h.coerceIn(minH(), GRID_ROWS)
    val cx = x.coerceIn(0, GRID_COLS - cw)
    val cy = y.coerceIn(0, GRID_ROWS - ch)
    return when (this) {
        is DashboardItem.AppShortcut -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.SplitPair -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.LaunchBar -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.BuiltinWidget -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.SystemWidget -> copy(x = cx, y = cy, w = cw, h = ch)
    }
}

/** True when the two tiles' cell rectangles share at least one cell. */
fun DashboardItem.overlaps(other: DashboardItem): Boolean =
    DashboardStore.rectanglesOverlap(x, y, w, h, other.x, other.y, other.w, other.h)

/**
 * Persists the 3 swipeable dashboards (each an ordered list of [DashboardItem])
 * to SharedPreferences as JSON. The layout is the user's, so it survives restarts.
 *
 * All grid rules live here so add, drag, resize, load-time repair and the
 * edit-mode ghost preview share one definition of "fits".
 */
object DashboardStore {

    const val PAGE_COUNT = 3

    private const val PREFS = "dashboard_layout_prefs"
    private const val KEY_PAGES = "pages"
    /** Previous good layout, kept so a corrupt write never costs the user everything. */
    private const val KEY_PAGES_BACKUP = "pages_backup"
    private const val KEY_VERSION = "schema"
    private const val TAG = "DashboardStore"

    /**
     * Layout schema version written with every save. Bump it when the JSON
     * shape changes and add the migration to [load]; readers must keep
     * accepting every older version, so a downgrade-then-upgrade never wipes
     * a layout.
     *
     *  1: `{"v":1,"pages":[[tile...], ...]}`. Before v1 the value was the bare
     *     pages array, which is still accepted.
     */
    private const val SCHEMA_VERSION = 1

    /**
     * Tiles this build does not understand (a type or builtin kind added by a
     * newer version, seen after a downgrade) are carried through untouched, per
     * page, and written back on the next save instead of being silently
     * dropped. Held here because the in-memory model has no slot for them.
     */
    private val retained = HashMap<Int, MutableList<JSONObject>>()

    /** Default layout when nothing is saved yet: map, directions and music on page 1. */
    private fun defaultPages(): List<List<DashboardItem>> = listOf(
        autoPlace(
            listOf(
                DashboardItem.BuiltinWidget(BuiltinKind.NAVMAP),
                DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, w = 4, h = 3),
                DashboardItem.BuiltinWidget(BuiltinKind.MEDIA)
            )
        ),
        emptyList(),
        emptyList()
    )

    fun load(context: Context): List<List<DashboardItem>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PAGES, null) ?: return defaultPages()

        // A corrupt primary value falls back to the last good layout rather
        // than to the defaults; only when both are unreadable does the user
        // lose their arrangement, and then it is logged.
        val parsed = parsePages(raw)
            ?: prefs.getString(KEY_PAGES_BACKUP, null)?.let { backup ->
                Log.w(TAG, "Saved layout unreadable, restoring the previous one")
                parsePages(backup)
            }
            ?: run {
                Log.e(TAG, "Saved layout and its backup are both unreadable; using defaults")
                return defaultPages()
            }

        // Always return exactly PAGE_COUNT pages. Tiles that predate grid
        // coordinates (x = -1) are flowed in; any overlap left by an older
        // grid size, a clamp, or a hand-edited file is repaired so two tiles
        // never share a cell.
        return List(PAGE_COUNT) { p ->
            val page = parsed.getOrElse(p) { emptyList() }
            repairOverlaps(if (page.any { it.x < 0 }) autoPlace(page) else page)
        }
    }

    /**
     * Parses either schema (bare pages array, or the versioned object) into
     * pages of tiles; null if the text is not a layout at all. Unknown tiles
     * are stashed in [retained] for the next [save].
     */
    internal fun parsePages(raw: String): List<List<DashboardItem>>? = runCatching {
        val trimmed = raw.trim()
        val pages = if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val v = obj.optInt(KEY_VERSION, 1)
            if (v > SCHEMA_VERSION) Log.w(TAG, "Layout schema v$v is newer than this build (v$SCHEMA_VERSION)")
            obj.optJSONArray(KEY_PAGES) ?: JSONArray()
        } else {
            JSONArray(trimmed)
        }
        retained.clear()
        (0 until pages.length()).map { p ->
            val page = pages.optJSONArray(p) ?: JSONArray()
            (0 until page.length()).mapNotNull { i ->
                val o = page.optJSONObject(i) ?: return@mapNotNull null
                o.toItem() ?: run {
                    retained.getOrPut(p) { mutableListOf() }.add(o)
                    null
                }
            }
        }
    }.onFailure { Log.w(TAG, "Layout parse failed", it) }.getOrNull()

    /** The versioned JSON document [save] writes, including any [retained] tiles. */
    internal fun serializePages(pages: List<List<DashboardItem>>): String {
        val json = JSONArray()
        pages.take(PAGE_COUNT).forEachIndexed { p, page ->
            val arr = JSONArray()
            page.forEach { arr.put(it.toJson()) }
            retained[p]?.forEach { arr.put(it) }
            json.put(arr)
        }
        return JSONObject().put(KEY_VERSION, SCHEMA_VERSION).put(KEY_PAGES, json).toString()
    }

    fun save(context: Context, pages: List<List<DashboardItem>>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val doc = serializePages(pages)
        val previous = prefs.getString(KEY_PAGES, null)
        prefs.edit().apply {
            // Keep what was there as the fallback for the next load, unless it
            // is the same text (nothing to gain) or unreadable (nothing to keep).
            if (previous != null && previous != doc && parsePagesQuietly(previous)) {
                putString(KEY_PAGES_BACKUP, previous)
            }
            putString(KEY_PAGES, doc)
        }.apply()
    }

    /** True if [raw] parses as a layout, without touching [retained]. */
    private fun parsePagesQuietly(raw: String): Boolean {
        val keep = HashMap(retained)
        val ok = parsePages(raw) != null
        retained.clear(); retained.putAll(keep)
        return ok
    }

    /** First cell where a [w] x [h] tile fits without overlapping [items]. */
    fun firstFreeCell(items: List<DashboardItem>, w: Int, h: Int): Pair<Int, Int>? {
        val occ = occupancy(items)
        return firstFree(occ, w.coerceIn(1, GRID_COLS), h.coerceIn(1, GRID_ROWS))
    }

    /**
     * True when a proposed tile rectangle is fully in bounds and does not touch
     * another tile. [ignoredIndex] is the tile currently being moved or resized.
     */
    fun canPlace(
        items: List<DashboardItem>,
        ignoredIndex: Int?,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ): Boolean {
        if (!inBounds(x, y, w, h)) return false
        return items.withIndex().none { (index, item) ->
            index != ignoredIndex && rectanglesOverlap(x, y, w, h, item.x, item.y, item.w, item.h)
        }
    }

    /**
     * Moves tile [index] so its top-left is at ([x],[y]), resolving collisions
     * instead of refusing them:
     *
     *  1. free target → plain move;
     *  2. exactly one tile in the way that fits in the vacated rectangle → swap;
     *  3. otherwise every tile in the way is nudged to its nearest free cell.
     *
     * Returns the new page, or null when the tiles in the way have nowhere to go
     * (the caller then keeps the layout and tells the user).
     */
    fun moveResolving(items: List<DashboardItem>, index: Int, x: Int, y: Int): List<DashboardItem>? {
        val mover = items.getOrNull(index) ?: return null
        if (!inBounds(x, y, mover.w, mover.h)) return null
        val moved = mover.withCell(x, y, mover.w, mover.h)
        if (canPlace(items, index, x, y, mover.w, mover.h)) {
            return items.mapIndexed { i, it -> if (i == index) moved else it }
        }

        val blocking = items.withIndex().filter { (i, it) -> i != index && it.overlaps(moved) }

        // Swap: one tile in the way, and it fits where the mover came from
        // without touching anything else.
        if (blocking.size == 1) {
            val (bi, b) = blocking.single()
            val swapped = b.withCell(mover.x, mover.y, b.w, b.h)
            val others = items.filterIndexed { i, _ -> i != index && i != bi }
            val clear = swapped.x == mover.x && swapped.y == mover.y &&
                !swapped.overlaps(moved) && others.none { it.overlaps(swapped) }
            if (clear) {
                return items.mapIndexed { i, it ->
                    when (i) {
                        index -> moved
                        bi -> swapped
                        else -> it
                    }
                }
            }
        }

        // Nudge: place the mover, then relocate each blocked tile (largest
        // first, so small ones fill the gaps) to the nearest free cell.
        val result = items.toMutableList()
        result[index] = moved
        val fixed = items.indices.filter { i -> i != index && blocking.none { it.index == i } }
            .map { result[it] }.toMutableList()
        fixed += moved
        for ((bi, b) in blocking.sortedByDescending { it.value.w * it.value.h }) {
            val cell = nearestFreeCell(fixed, b.w, b.h, b.x, b.y) ?: return null
            val placed = b.withCell(cell.first, cell.second, b.w, b.h)
            result[bi] = placed
            fixed += placed
        }
        return result
    }

    /**
     * Free cell for a [w] x [h] tile closest to ([nearX],[nearY]) given the
     * already-placed [items]; null when the page is full.
     */
    fun nearestFreeCell(items: List<DashboardItem>, w: Int, h: Int, nearX: Int, nearY: Int): Pair<Int, Int>? {
        val occ = occupancy(items)
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE
        for (y in 0..GRID_ROWS - h) for (x in 0..GRID_COLS - w) {
            if (!fits(occ, x, y, w, h)) continue
            val d = abs(x - nearX) + abs(y - nearY)
            if (d < bestDist) { bestDist = d; best = x to y }
        }
        return best
    }

    /**
     * Keeps the first of any overlapping tiles where it is and relocates the
     * later ones to the nearest free cell. A tile with no room left is dropped
     * rather than drawn on top of another one.
     */
    fun repairOverlaps(items: List<DashboardItem>): List<DashboardItem> {
        val placed = mutableListOf<DashboardItem>()
        for (item in items) {
            val fixed = item.withCell(item.x, item.y, item.w, item.h)
            if (placed.none { it.overlaps(fixed) }) {
                placed += fixed
                continue
            }
            val full = nearestFreeCell(placed, fixed.w, fixed.h, fixed.x, fixed.y)
            if (full != null) {
                placed += fixed.withCell(full.first, full.second, fixed.w, fixed.h)
                continue
            }
            // No room at its size: shrink to the minimum span before giving up.
            val small = nearestFreeCell(placed, fixed.minW(), fixed.minH(), fixed.x, fixed.y) ?: continue
            placed += fixed.withCell(small.first, small.second, fixed.minW(), fixed.minH())
        }
        return placed
    }

    private fun inBounds(x: Int, y: Int, w: Int, h: Int): Boolean =
        x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= GRID_COLS && y + h <= GRID_ROWS

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

    internal fun rectanglesOverlap(
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
            is DashboardItem.LaunchBar ->
                JSONObject().put("t", "bar").put("pkgs", JSONArray(packages))
            is DashboardItem.BuiltinWidget -> JSONObject().put("t", "builtin").put("k", kind.name)
            is DashboardItem.SystemWidget -> JSONObject().put("t", "widget").put("id", appWidgetId)
        }
        return o.put("gx", x).put("gy", y).put("gw", w).put("gh", h)
    }

    /** Copy with x = -1, the sentinel load() uses to auto-place migrated tiles. */
    private fun DashboardItem.markUnplaced(): DashboardItem = when (this) {
        is DashboardItem.AppShortcut -> copy(x = -1)
        is DashboardItem.SplitPair -> copy(x = -1)
        is DashboardItem.LaunchBar -> copy(x = -1)
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
            "bar" -> {
                val arr = optJSONArray("pkgs") ?: JSONArray()
                val pkgs = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                place(DashboardItem.LaunchBar(pkgs))
            }
            // Default span comes from the kind (a clock is 3x2, a map 5x3), so a
            // legacy tile without coordinates is re-flowed at its proper size.
            "builtin" -> runCatching { BuiltinKind.valueOf(optString("k")) }.getOrNull()
                ?.let { place(DashboardItem.BuiltinWidget(it, w = it.defaultW, h = it.defaultH)) }
            "widget" -> optInt("id", -1).takeIf { it != -1 }
                ?.let { place(DashboardItem.SystemWidget(it)) }
            else -> null
        }
    }
}
