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
    CAN_MON("CAN monitor (debug)")
}

/**
 * One tile on a dashboard page:
 *  - [AppShortcut]  a small icon that launches an installed app,
 *  - [BuiltinWidget] one of our own large cards (Maps / media / OBD),
 *  - [SystemWidget]  a real Android app-widget, hosted via [WidgetHostHolder].
 */
sealed interface DashboardItem {
    data class AppShortcut(val packageName: String) : DashboardItem
    data class BuiltinWidget(val kind: BuiltinKind) : DashboardItem
    data class SystemWidget(val appWidgetId: Int) : DashboardItem
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
        is DashboardItem.AppShortcut -> JSONObject().put("t", "app").put("pkg", packageName)
        is DashboardItem.BuiltinWidget -> JSONObject().put("t", "builtin").put("k", kind.name)
        is DashboardItem.SystemWidget -> JSONObject().put("t", "widget").put("id", appWidgetId)
    }

    private fun JSONObject.toItem(): DashboardItem? = when (optString("t")) {
        "app" -> optString("pkg").takeIf { it.isNotBlank() }?.let { DashboardItem.AppShortcut(it) }
        "builtin" -> runCatching { BuiltinKind.valueOf(optString("k")) }.getOrNull()
            ?.let { DashboardItem.BuiltinWidget(it) }
        "widget" -> optInt("id", -1).takeIf { it != -1 }?.let { DashboardItem.SystemWidget(it) }
        else -> null
    }
}
