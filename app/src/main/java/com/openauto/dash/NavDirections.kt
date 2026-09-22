package com.openauto.dash

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The next manoeuvre of a running navigation app, as shown on the dashboard.
 *
 * Google Maps (and Waze) cannot be embedded on this unit, but while they
 * navigate they post an ongoing notification carrying the turn icon, the
 * distance to the turn, the street and the ETA line. [NavDirections] reads that
 * notification and publishes it here for the Directions tile and the banner
 * over the MapLibre map.
 */
data class NavState(
    val active: Boolean = false,
    /** e.g. "Turn right onto Rue de Rivoli". */
    val instruction: String = "",
    /** Distance to the manoeuvre, e.g. "300 m". Empty when unknown. */
    val distance: String = "",
    /** ETA line as posted, e.g. "12 min · 6.4 km · 09:48". Empty when unknown. */
    val eta: String = "",
    /** The manoeuvre arrow, usually a white glyph on transparent. */
    val icon: Bitmap? = null,
    val packageName: String = ""
) {
    /** Distance split into value and unit: "300 m" → ("300", "m"). */
    val distanceParts: Pair<String, String>
        get() {
            val m = DISTANCE.find(distance) ?: return distance to ""
            return m.groupValues[1] to m.groupValues[2]
        }

    /** ETA segments as separate chips: "12 min · 6.4 km · 09:48" → three entries. */
    val etaParts: List<String>
        get() = eta.split('·', '•', '|').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        val DISTANCE = Regex("""(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft|yd)\b""")
    }
}

object NavDirections {
    /** Navigation apps whose turn-by-turn notification we read. */
    val PACKAGES = setOf("com.google.android.apps.maps", "com.waze")

    /** "In", "Dans", "En", "A", "À" (+ optional comma) at the start of an instruction. */
    private val LEADING_PREPOSITION = Regex("^(in|dans|en|a|à)\\s*,?\\s*", RegexOption.IGNORE_CASE)

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    /** Notification key currently driving [state], so its removal clears the route. */
    @Volatile
    private var currentKey: String? = null

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        val parsed = parse(context, sbn) ?: return
        currentKey = sbn.key
        _state.value = parsed
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == currentKey) {
            currentKey = null
            _state.value = NavState()
        }
    }

    fun clear() {
        currentKey = null
        _state.value = NavState()
    }

    /**
     * Extracts a [NavState] from a notification, or null when it is not a
     * turn-by-turn notification (Maps also posts plain "running" notices).
     */
    private fun parse(context: Context, sbn: StatusBarNotification): NavState? {
        val n = sbn.notification ?: return null
        if (!sbn.isOngoing) return null
        val extras = n.extras

        val lines = mutableListOf<String>()
        fun add(cs: CharSequence?) {
            val t = cs?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && t !in lines) lines += t
        }
        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))

        var icon: Bitmap? = runCatching {
            n.getLargeIcon()?.loadDrawable(context)?.toBitmap()
        }.getOrNull()

        // Maps often uses a custom layout with nothing in the extras, so inflate
        // the remote views and read their text and image views directly.
        if (lines.isEmpty() || icon == null) {
            @Suppress("DEPRECATION")
            for (rv in listOf(n.bigContentView, n.contentView, n.headsUpContentView)) {
                val (texts, bmp) = remoteContent(context, rv)
                texts.forEach { add(it) }
                if (icon == null) icon = bmp
                if (lines.isNotEmpty() && icon != null) break
            }
        }
        if (lines.isEmpty()) return null
        return fromLines(lines, icon, sbn.packageName)
    }

    /**
     * Builds a [NavState] from the text lines of a navigation notification
     * (title, text, sub text, big text or the custom layout's text views, in
     * that order, de-duplicated). Null when the lines don't describe a turn.
     * Pure so the Maps / Waze formats can be unit tested.
     */
    internal fun fromLines(lines: List<String>, icon: Bitmap?, packageName: String): NavState? {
        if (lines.isEmpty()) return null

        val eta = lines.firstOrNull { it.contains('·') || it.contains('•') } ?: lines.firstOrNull {
            ETA_LINE.containsMatchIn(it) && NavState.DISTANCE.find(it)?.value != it
        }
        val rest = lines.filter { it != eta }
        val distanceOnly = rest.firstOrNull { NavState.DISTANCE.matchEntire(it) != null }

        var distance = distanceOnly.orEmpty()
        var instruction = rest.firstOrNull { it != distanceOnly }.orEmpty()
        if (distance.isEmpty()) {
            // "In 300 m, turn right" style: pull the distance out of the instruction.
            NavState.DISTANCE.find(instruction)?.let { m ->
                distance = m.value
                instruction = instruction.replace(m.value, "").trim().trimStart(',', '-', '–', ':', ' ')
                    // "In 300 m, turn right" leaves "In , turn right"; drop the preposition.
                    .replace(LEADING_PREPOSITION, "")
                    .replaceFirstChar { it.uppercase() }
            }
        }
        if (instruction.isEmpty() && distance.isEmpty()) return null
        // Not a turn-by-turn card unless it has a distance or an ETA line.
        if (distance.isEmpty() && eta == null) return null

        return NavState(
            active = true,
            instruction = instruction.ifEmpty { "Continue" },
            distance = distance,
            eta = eta.orEmpty(),
            icon = icon,
            packageName = packageName
        )
    }

    private val ETA_LINE = Regex("""\b\d+\s*(min|h|hr)\b""", RegexOption.IGNORE_CASE)

    private fun remoteContent(context: Context, rv: RemoteViews?): Pair<List<String>, Bitmap?> {
        rv ?: return emptyList<String>() to null
        return runCatching {
            val root = rv.apply(context, FrameLayout(context))
            val texts = mutableListOf<String>()
            var bmp: Bitmap? = null
            fun walk(v: View) {
                when (v) {
                    is TextView -> v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    is ImageView -> if (bmp == null) bmp = (v.drawable as? BitmapDrawable)?.bitmap
                        ?: v.drawable?.let { d -> runCatching { d.toBitmap() }.getOrNull() }
                    is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(root)
            texts to bmp
        }.getOrDefault(emptyList<String>() to null)
    }
}
