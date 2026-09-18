package com.openauto.dash

import android.content.Context

enum class WidgetType(val label: String) {
    MAPS("Left Screen App Widget (e.g. Google Maps)"),
    MEDIA("Music Player"),
    TELEMETRY("OBD Telemetry"),
    SYSTEM_WIDGETS("Right Screen App Widget")
}

object WidgetConfig {
    private const val PREFS_NAME = "dashboard_widgets_prefs"
    private const val KEY_WIDGETS = "active_widgets"

    fun getActiveWidgets(context: Context): List<WidgetType> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_WIDGETS, null)
        if (saved == null) {
            // Default configuration
            return listOf(WidgetType.MAPS, WidgetType.MEDIA, WidgetType.TELEMETRY)
        }
        if (saved.isEmpty()) return emptyList()
        return saved.split(",").mapNotNull {
            runCatching { WidgetType.valueOf(it) }.getOrNull()
        }
    }

    fun saveActiveWidgets(context: Context, widgets: List<WidgetType>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serialized = widgets.joinToString(",") { it.name }
        prefs.edit().putString(KEY_WIDGETS, serialized).apply()
    }
}
