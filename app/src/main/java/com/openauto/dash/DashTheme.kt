package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class DashThemeMode(val title: String, val description: String) {
    AUTO("Auto", "Follow the car's day/night mode"),
    ORIGINAL("Original", "The first launcher look: flat cards, twin needle gauges"),
    AURORA("Aurora Glass", "Glass panels, glowing gauges, gradient controls"),
    NEON_DARK("Neon Dark", "Blue + violet futuristic cockpit"),
    CLEAN_LIGHT("Clean Light", "Bright, minimal and easy to read"),
    DARK_GLASS("Dark Glass", "Premium dark glass aesthetic"),
    SPORTY("Sporty", "Black + red performance cockpit")
}

/**
 * Colours plus a few style knobs for one dashboard theme.
 *
 * [Glass] switches cards to translucent gradient panels with a hairline border
 * and a specular top edge over a gradient background. [Glow] (0..1) scales the
 * halo drawn behind gauges, readouts and primary controls; light themes keep it
 * at 0 so nothing smears in sunlight. [Accent2] is the far end of the accent
 * gradient used for the gauge sweep and gradient buttons. [Original] swaps the
 * media, telemetry, gauge, meter-chip and top-bar widgets back to their first
 * designs (see OriginalTiles.kt).
 */
data class DashPalette(
    val Background: Color, val Bar: Color, val Card: Color, val CardHi: Color,
    val Accent: Color, val Speed: Color, val Rpm: Color, val Warning: Color,
    val Good: Color, val Muted: Color, val TextPrimary: Color, val TextSecondary: Color,
    val Accent2: Color = Accent,
    val Line: Color = Color.White.copy(alpha = 0.10f),
    val Glass: Boolean = false,
    val Glow: Float = 0f,
    val Original: Boolean = false,
    val BackgroundStops: List<Color> = listOf(Background, Background)
)

private val AutoDarkPalette = DashPalette(
    Color(0xFF0B0C0F), Color(0xFF141518), Color(0xFF1E2024), Color(0xFF2A2D33),
    Color(0xFF8AB4F8), Color(0xFF8AB4F8), Color(0xFFF6AD7B), Color(0xFFF28B82),
    Color(0xFF81C995), Color(0xFF9AA0A6), Color(0xFFE8EAED), Color(0xFF9AA0A6),
    Accent2 = Color(0xFFC58AF9), Glow = 0.35f
)
private val AutoLightPalette = DashPalette(
    Color(0xFFF1F3F4), Color.White, Color.White, Color(0xFFE3E6EA),
    Color(0xFF1A73E8), Color(0xFF1A73E8), Color(0xFFE8710A), Color(0xFFD93025),
    Color(0xFF188038), Color(0xFF5F6368), Color(0xFF202124), Color(0xFF5F6368),
    Accent2 = Color(0xFF7B4DFF), Line = Color.Black.copy(alpha = 0.08f)
)
// Original: the pre-Aurora day/night palettes with no glow, no accent gradient
// and no rim lines, so shared components render exactly as they first did.
private val OriginalDarkPalette = AutoDarkPalette.copy(
    Accent2 = AutoDarkPalette.Accent, Line = Color.Transparent, Glow = 0f, Original = true
)
private val OriginalLightPalette = AutoLightPalette.copy(
    Accent2 = AutoLightPalette.Accent, Line = Color.Transparent, Glow = 0f, Original = true
)
private val AuroraPalette = DashPalette(
    Background = Color(0xFF080D1C), Bar = Color(0xCC0B1226), Card = Color(0xE60E1730), CardHi = Color(0x24FFFFFF),
    Accent = Color(0xFF5AD0FF), Speed = Color(0xFF5AD0FF), Rpm = Color(0xFF4EE3A5), Warning = Color(0xFFFF5D7A),
    Good = Color(0xFF4EE3A5), Muted = Color(0xFF8593B3), TextPrimary = Color(0xFFF2F7FF), TextSecondary = Color(0xFFAEBBD6),
    Accent2 = Color(0xFF9B7BFF), Line = Color.White.copy(alpha = 0.12f), Glass = true, Glow = 1f,
    BackgroundStops = listOf(Color(0xFF0E1730), Color(0xFF080D1C), Color(0xFF130F2C))
)
private val NeonDarkPalette = DashPalette(
    Color(0xFF030817), Color(0xFF071126), Color(0xFF0A1935), Color(0xFF13294A),
    Color(0xFF4B9BFF), Color(0xFF43A5FF), Color(0xFFA46BFF), Color(0xFFFF5F72),
    Color(0xFF36E0A0), Color(0xFF8292B0), Color(0xFFF4F7FF), Color(0xFFB5C0D6),
    Accent2 = Color(0xFFA46BFF), Glow = 0.8f,
    BackgroundStops = listOf(Color(0xFF050C22), Color(0xFF030817))
)
private val CleanLightPalette = DashPalette(
    Color(0xFFF5F7FA), Color.White, Color.White, Color(0xFFEAF0F7),
    Color(0xFF246BCE), Color(0xFF246BCE), Color(0xFF8A5A00), Color(0xFFC62828),
    Color(0xFF177245), Color(0xFF667085), Color(0xFF101828), Color(0xFF667085),
    Accent2 = Color(0xFF6A4FD8), Line = Color.Black.copy(alpha = 0.08f)
)
private val DarkGlassPalette = DashPalette(
    Color(0xFF05070B), Color(0xCC101722), Color(0xCC101A2A), Color(0xCC1B2A42),
    Color(0xFF68A8FF), Color(0xFF68A8FF), Color(0xFF9C7BFF), Color(0xFFFF6B7A),
    Color(0xFF55D6A5), Color(0xFF8B98AD), Color(0xFFF7F9FC), Color(0xFFB6C0D0),
    Accent2 = Color(0xFF9C7BFF), Glass = true, Glow = 0.6f,
    BackgroundStops = listOf(Color(0xFF0A1220), Color(0xFF05070B), Color(0xFF120D24))
)
private val SportyPalette = DashPalette(
    Color(0xFF07080A), Color(0xFF0D0F12), Color(0xFF12161B), Color(0xFF20262D),
    Color(0xFFFF334A), Color(0xFFFF334A), Color(0xFFFF8A3D), Color(0xFFFF334A),
    Color(0xFF4DDC7A), Color(0xFF8B929B), Color(0xFFF6F7F9), Color(0xFFB3B8C0),
    Accent2 = Color(0xFFFF8A3D), Glow = 0.6f,
    BackgroundStops = listOf(Color(0xFF0D0F12), Color(0xFF07080A))
)

/** How the screen is divided: pages only, or a permanent Google Maps dock beside them. */
enum class DashLayout(val title: String, val description: String) {
    GRID("Full", "Swipeable pages fill the screen"),
    MAPS_LEFT("\u25c0 Map", "Google Maps docked on the left half, pages swipe on the right"),
    MAPS_RIGHT("Map \u25b6", "Google Maps docked on the right half, pages swipe on the left")
}

object DashLayoutStore {
    private const val PREFS = "dashboard_layout"
    private const val KEY = "mode"

    fun load(context: Context): DashLayout = runCatching {
        DashLayout.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, DashLayout.GRID.name) ?: DashLayout.GRID.name
        )
    }.getOrDefault(DashLayout.GRID)

    fun save(context: Context, layout: DashLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, layout.name).apply()
    }
}

object DashThemeStore {
    private const val PREFS = "dashboard_theme"
    private const val KEY = "mode"

    fun load(context: Context): DashThemeMode = runCatching {
        DashThemeMode.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, DashThemeMode.AUTO.name) ?: DashThemeMode.AUTO.name
        )
    }.getOrDefault(DashThemeMode.AUTO)

    fun save(context: Context, mode: DashThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, mode.name).apply()
    }
}

object DashColors {
    private var current by mutableStateOf(AutoDarkPalette)

    @Composable
    fun Sync(mode: DashThemeMode) {
        val target = when (mode) {
            DashThemeMode.AUTO -> if (isSystemInDarkTheme()) AutoDarkPalette else AutoLightPalette
            DashThemeMode.ORIGINAL -> if (isSystemInDarkTheme()) OriginalDarkPalette else OriginalLightPalette
            DashThemeMode.AURORA -> AuroraPalette
            DashThemeMode.NEON_DARK -> NeonDarkPalette
            DashThemeMode.CLEAN_LIGHT -> CleanLightPalette
            DashThemeMode.DARK_GLASS -> DarkGlassPalette
            DashThemeMode.SPORTY -> SportyPalette
        }
        if (current != target) current = target
    }

    val Background get() = current.Background
    val Bar get() = current.Bar
    val Card get() = current.Card
    val CardHi get() = current.CardHi
    val Accent get() = current.Accent
    val Accent2 get() = current.Accent2
    val Speed get() = current.Speed
    val Rpm get() = current.Rpm
    val Warning get() = current.Warning
    val Good get() = current.Good
    val Muted get() = current.Muted
    val TextPrimary get() = current.TextPrimary
    val TextSecondary get() = current.TextSecondary
    val Line get() = current.Line
    val Glass get() = current.Glass
    val Glow get() = current.Glow
    val Original get() = current.Original
    val BackgroundStops get() = current.BackgroundStops

    /** Diagonal accent → accent2 gradient for primary controls. */
    val AccentBrush: Brush get() = Brush.linearGradient(listOf(current.Accent, current.Accent2))

    /** Text colour that reads on top of [AccentBrush]. */
    val OnAccent: Color get() = if (current.Glass || current.Glow > 0f) Color(0xFF03111F) else current.Background
}
