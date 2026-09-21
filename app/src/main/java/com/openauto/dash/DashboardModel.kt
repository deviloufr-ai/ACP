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
 * One tile on a dashboard page:
 *  - [AppShortcut]  a small icon that launches an installed app,
 *  - [BuiltinWidget] one of our own large cards (Maps / media / OBD),
 *  - [SystemWidget]  a real Android app-widget, hosted via [WidgetHostHolder].
 */
sealed interface DashboardItem {
    data class AppShortcut(val packageName: String, val half: Boolean = false) : DashboardItem
    /** Launches two apps side-by-side in split-screen (see [SplitLauncher]). */
    data class SplitPair(
        val primaryPackage: String,
        val secondaryPackage: String,
        val half: Boolean = false
    ) : DashboardItem
    data class BuiltinWidget(val kind: BuiltinKind, val weight: Float = 1f, val half: Boolean = false) : DashboardItem
    data class SystemWidget(val appWidgetId: Int, val weight: Float = 1f, val half: Boolean = false) : DashboardItem
}

/**
 * A "half" tile takes only a share of the row height so several stack in one
 * column instead of each eating a full-height slot. Any tile — widget card or
 * compact icon (shortcut / split pair) — can be stacked this way.
 */
fun DashboardItem.isHalf(): Boolean = when (this) {
    is DashboardItem.AppShortcut -> half
    is DashboardItem.SplitPair -> half
    is DashboardItem.BuiltinWidget -> half
    is DashboardItem.SystemWidget -> half
}

/** Returns a copy toggled between stacked (half) and standalone height. */
fun DashboardItem.withHalf(h: Boolean): DashboardItem = when (this) {
    is DashboardItem.AppShortcut -> copy(half = h)
    is DashboardItem.SplitPair -> copy(half = h)
    is DashboardItem.BuiltinWidget -> copy(half = h)
    is DashboardItem.SystemWidget -> copy(half = h)
}

/** Compact fixed-width icon tiles, as opposed to weighted widget cards. */
fun DashboardItem.isCompactTile(): Boolean =
    this is DashboardItem.AppShortcut || this is DashboardItem.SplitPair

/**
 * Relative width a tile occupies in its dashboard row. Widgets share the row by
 * weight (so they can be made wider/narrower); app shortcuts stay a fixed width.
 */
fun DashboardItem.tileWeight(): Float = when (this) {
    is DashboardItem.BuiltinWidget -> weight
    is DashboardItem.SystemWidget -> weight
    is DashboardItem.AppShortcut -> 1f
    is DashboardItem.SplitPair -> 1f
}

/** Returns a copy of this item with a new row weight (no-op for compact tiles). */
fun DashboardItem.withWeight(newWeight: Float): DashboardItem = when (this) {
    is DashboardItem.BuiltinWidget -> copy(weight = newWeight)
    is DashboardItem.SystemWidget -> copy(weight = newWeight)
    is DashboardItem.AppShortcut -> this
    is DashboardItem.SplitPair -> this
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
        listOf(
            DashboardItem.BuiltinWidget(BuiltinKind.NAVMAP),
            DashboardItem.BuiltinWidget(BuiltinKind.MEDIA)
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

        // Always return exactly PAGE_COUNT pages (pad with empty / truncate extras).
        return List(PAGE_COUNT) { p -> parsed.getOrElse(p) { emptyList() } }
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

    private fun DashboardItem.toJson(): JSONObject = when (this) {
        is DashboardItem.AppShortcut -> JSONObject().put("t", "app").put("pkg", packageName).put("h", half)
        is DashboardItem.SplitPair ->
            JSONObject().put("t", "split").put("a", primaryPackage).put("b", secondaryPackage).put("h", half)
        is DashboardItem.BuiltinWidget ->
            JSONObject().put("t", "builtin").put("k", kind.name).put("w", weight.toDouble()).put("h", half)
        is DashboardItem.SystemWidget ->
            JSONObject().put("t", "widget").put("id", appWidgetId).put("w", weight.toDouble()).put("h", half)
    }

    private fun JSONObject.toItem(): DashboardItem? = when (optString("t")) {
        "app" -> optString("pkg").takeIf { it.isNotBlank() }
            ?.let { DashboardItem.AppShortcut(it, optBoolean("h", false)) }
        "split" -> {
            val a = optString("a")
            val b = optString("b")
            if (a.isNotBlank() && b.isNotBlank()) DashboardItem.SplitPair(a, b, optBoolean("h", false)) else null
        }
        "builtin" -> runCatching { BuiltinKind.valueOf(optString("k")) }.getOrNull()
            ?.let { DashboardItem.BuiltinWidget(it, readWeight(), optBoolean("h", false)) }
        "widget" -> optInt("id", -1).takeIf { it != -1 }
            ?.let { DashboardItem.SystemWidget(it, readWeight(), optBoolean("h", false)) }
        else -> null
    }

    /** Persisted tile weight, clamped to the same range the resize handle allows. */
    private fun JSONObject.readWeight(): Float =
        optDouble("w", 1.0).toFloat().coerceIn(MIN_TILE_WEIGHT, MAX_TILE_WEIGHT)

    const val MIN_TILE_WEIGHT = 0.4f
    const val MAX_TILE_WEIGHT = 4f
}
