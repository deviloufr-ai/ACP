package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The dashboard design. Every theme comes in a dark and a light version; which
 * one shows is picked by [DashAppearance]. Enum names are persisted, so the old
 * names (AUTO, NEON_DARK, CLEAN_LIGHT, DARK_GLASS) stay even where the title moved on.
 */
enum class DashThemeMode(val title: String, val description: String) {
    AUTO("Standard", "Simple cards with softly glowing gauges"),
    ORIGINAL("Original", "The first launcher look: flat cards, twin needle gauges"),
    AURORA("Aurora Glass", "Glass panels, glowing gauges, gradient controls"),
    NEON_DARK("Neon", "Blue + violet futuristic cockpit"),
    CLEAN_LIGHT("Clean", "Minimal, high contrast and easy to read"),
    DARK_GLASS("Premium Glass", "Smoked glass by night, frosted glass by day"),
    SPORTY("Sporty", "Red performance cockpit"),
    FLOATING("Floating", "No tile backgrounds: widgets and icons sit on the backdrop"),
    ORBIT("Orbit", "Everything round: a spinning record, ring gauges, bubbles"),
    COCKPIT("Cockpit", "Chrome-ringed analog dials and toggle switches on stitched leather"),
    HORIZON("Horizon", "No widgets, just the sky and the road ahead"),
    TAPE_DECK("Tape Deck", "80s synthwave head unit: cassette, neon grid, LED digits")
}

/** Dark or light version of the theme; [AUTO] follows the car's day/night mode. */
enum class DashAppearance(val title: String) {
    AUTO("Auto"),
    DARK("Dark"),
    LIGHT("Light")
}

/**
 * Whole-design variants. A skin swaps more than colours: its own page
 * background, top bar and renderers for the main widgets (see Skins.kt).
 * [STANDARD] is every colour-only theme.
 */
enum class DashSkin { STANDARD, ORBIT, COCKPIT, HORIZON, TAPE_DECK }

/**
 * Colours plus a few style knobs for one dashboard theme.
 *
 * [Glass] switches cards to translucent gradient panels with a hairline border
 * and a specular top edge over a gradient background. [Glow] (0..1) scales the
 * halo drawn behind gauges, readouts and primary controls; light themes keep it
 * at 0 so nothing smears in sunlight. [Accent2] is the far end of the accent
 * gradient used for the gauge sweep and gradient buttons. [Original] swaps the
 * media, telemetry, gauge and meter-chip widgets back to their first designs
 * (see OriginalTiles.kt). [Bare] drops the tile cards and the fills
 * behind icons, chips and list rows (see itemFill), so content sits straight on
 * the page background; Card / CardHi still colour dialogs and buttons. [Skin]
 * picks a whole-design variant; skins are bare, their widgets draw their own shapes.
 * [Light] marks the day version of a theme: dark text on a pale page, so
 * anything that brightens with white (glass haze, highlights) must darken instead.
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
    val Bare: Boolean = false,
    val BackgroundStops: List<Color> = listOf(Background, Background),
    val Skin: DashSkin = DashSkin.STANDARD,
    val Light: Boolean = false
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
    Accent2 = Color(0xFF7B4DFF), Line = Color.Black.copy(alpha = 0.08f), Light = true
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
// Light glass: frosted white panels over a pale blue -> lilac wash.
private val AuroraLightPalette = DashPalette(
    Background = Color(0xFFF3F6FC), Bar = Color(0xCCFFFFFF), Card = Color(0xF2FFFFFF), CardHi = Color(0x140E1630),
    Accent = Color(0xFF0092D6), Speed = Color(0xFF0092D6), Rpm = Color(0xFF0E9F6A), Warning = Color(0xFFE0344F),
    Good = Color(0xFF0E9F6A), Muted = Color(0xFF6B7794), TextPrimary = Color(0xFF0E1630), TextSecondary = Color(0xFF4D5B7A),
    Accent2 = Color(0xFF7B5CF0), Line = Color(0x1A0E1630), Glass = true, Glow = 0.3f,
    BackgroundStops = listOf(Color(0xFFE6F0FF), Color(0xFFF6F8FC), Color(0xFFEFE9FF)), Light = true
)
private val NeonDarkPalette = DashPalette(
    Color(0xFF030817), Color(0xFF071126), Color(0xFF0A1935), Color(0xFF13294A),
    Color(0xFF4B9BFF), Color(0xFF43A5FF), Color(0xFFA46BFF), Color(0xFFFF5F72),
    Color(0xFF36E0A0), Color(0xFF8292B0), Color(0xFFF4F7FF), Color(0xFFB5C0D6),
    Accent2 = Color(0xFFA46BFF), Glow = 0.8f,
    BackgroundStops = listOf(Color(0xFF050C22), Color(0xFF030817))
)
private val NeonLightPalette = DashPalette(
    Color(0xFFF4F6FF), Color.White, Color.White, Color(0xFFE6EBFA),
    Color(0xFF2F6BFF), Color(0xFF2A7BFF), Color(0xFF8A4DFF), Color(0xFFE5364D),
    Color(0xFF0FA36B), Color(0xFF6A7390), Color(0xFF0A1330), Color(0xFF4A5577),
    Accent2 = Color(0xFF8A4DFF), Line = Color.Black.copy(alpha = 0.08f), Glow = 0.2f,
    BackgroundStops = listOf(Color(0xFFEBF0FF), Color(0xFFF7F8FF)), Light = true
)
private val CleanLightPalette = DashPalette(
    Color(0xFFF5F7FA), Color.White, Color.White, Color(0xFFEAF0F7),
    Color(0xFF246BCE), Color(0xFF246BCE), Color(0xFF8A5A00), Color(0xFFC62828),
    Color(0xFF177245), Color(0xFF667085), Color(0xFF101828), Color(0xFF667085),
    Accent2 = Color(0xFF6A4FD8), Line = Color.Black.copy(alpha = 0.08f), Light = true
)
private val CleanDarkPalette = DashPalette(
    Color(0xFF0F1115), Color(0xFF16191E), Color(0xFF1B1F25), Color(0xFF262B33),
    Color(0xFF6AA3F0), Color(0xFF6AA3F0), Color(0xFFE0A33A), Color(0xFFFF6B6B),
    Color(0xFF4CC38A), Color(0xFF8A93A3), Color(0xFFF2F4F7), Color(0xFFA3ABB9),
    Accent2 = Color(0xFF9A86F0), Line = Color.White.copy(alpha = 0.08f)
)
private val DarkGlassPalette = DashPalette(
    Color(0xFF05070B), Color(0xCC101722), Color(0xCC101A2A), Color(0xCC1B2A42),
    Color(0xFF68A8FF), Color(0xFF68A8FF), Color(0xFF9C7BFF), Color(0xFFFF6B7A),
    Color(0xFF55D6A5), Color(0xFF8B98AD), Color(0xFFF7F9FC), Color(0xFFB6C0D0),
    Accent2 = Color(0xFF9C7BFF), Glass = true, Glow = 0.6f,
    BackgroundStops = listOf(Color(0xFF0A1220), Color(0xFF05070B), Color(0xFF120D24))
)
private val FrostedGlassPalette = DashPalette(
    Color(0xFFF2F4F8), Color(0xCCFFFFFF), Color(0xE6FFFFFF), Color(0x140B1220),
    Color(0xFF2F72D6), Color(0xFF2F72D6), Color(0xFF7652E0), Color(0xFFD93A4C),
    Color(0xFF15A06E), Color(0xFF697489), Color(0xFF0B1220), Color(0xFF4B5567),
    Accent2 = Color(0xFF7652E0), Line = Color(0x1A0B1220), Glass = true, Glow = 0.2f,
    BackgroundStops = listOf(Color(0xFFE8EEF7), Color(0xFFF5F7FA), Color(0xFFEEEAF7)), Light = true
)
private val SportyPalette = DashPalette(
    Color(0xFF07080A), Color(0xFF0D0F12), Color(0xFF12161B), Color(0xFF20262D),
    Color(0xFFFF334A), Color(0xFFFF334A), Color(0xFFFF8A3D), Color(0xFFFF334A),
    Color(0xFF4DDC7A), Color(0xFF8B929B), Color(0xFFF6F7F9), Color(0xFFB3B8C0),
    Accent2 = Color(0xFFFF8A3D), Glow = 0.6f,
    BackgroundStops = listOf(Color(0xFF0D0F12), Color(0xFF07080A))
)
private val SportyLightPalette = DashPalette(
    Color(0xFFF5F5F6), Color.White, Color.White, Color(0xFFEBECEE),
    Color(0xFFE0162E), Color(0xFFE0162E), Color(0xFFE8650F), Color(0xFFE0162E),
    Color(0xFF1E9E4A), Color(0xFF6B7078), Color(0xFF111317), Color(0xFF555A63),
    Accent2 = Color(0xFFE8650F), Line = Color.Black.copy(alpha = 0.08f),
    BackgroundStops = listOf(Color.White, Color(0xFFECEDEF)), Light = true
)
// Floating: no cards, so the bar is transparent too and the backdrop is a calm
// gradient that text and gauges read on directly.
private val FloatingPalette = DashPalette(
    Color(0xFF06080D), Color.Transparent, Color(0xFF141A24), Color(0xFF1F2733),
    Color(0xFF7CC4FF), Color(0xFF7CC4FF), Color(0xFFFFB86B), Color(0xFFFF6B6B),
    Color(0xFF5EE3A1), Color(0xFF8A94A6), Color(0xFFF5F7FA), Color(0xFFB4BCC8),
    Accent2 = Color(0xFFB38CFF), Glow = 0.5f, Bare = true,
    BackgroundStops = listOf(Color(0xFF0C1424), Color(0xFF06080D), Color(0xFF0E0B1C))
)
private val FloatingLightPalette = DashPalette(
    Color(0xFFF4F6FA), Color.Transparent, Color.White, Color(0xFFE8ECF2),
    Color(0xFF1C7FD6), Color(0xFF1C7FD6), Color(0xFFD9791A), Color(0xFFE04848),
    Color(0xFF17A165), Color(0xFF687385), Color(0xFF0E141F), Color(0xFF4E5868),
    Accent2 = Color(0xFF7B55E0), Line = Color.Black.copy(alpha = 0.08f), Glow = 0.15f, Bare = true,
    BackgroundStops = listOf(Color(0xFFE6EEFA), Color(0xFFF6F8FB), Color(0xFFEFEAFA)), Light = true
)
// Skins: Card / CardHi only colour dialogs, menus and buttons; tiles are bare.
private val OrbitPalette = DashPalette(
    Color(0xFF0A0E1C), Color.Transparent, Color(0xFF151B30), Color(0x17FFFFFF),
    Color(0xFFFF7A59), Color(0xFFFF7A59), Color(0xFF3DDBC3), Color(0xFFFF5D7A),
    Color(0xFF3DDBC3), Color(0xFF8A92B6), Color(0xFFEEF1FF), Color(0xFFAEB5D3),
    Accent2 = Color(0xFF8A7BFF), Line = Color.White.copy(alpha = 0.10f), Glow = 0.8f, Bare = true,
    Skin = DashSkin.ORBIT
)
private val OrbitLightPalette = DashPalette(
    Color(0xFFF4F1FA), Color.Transparent, Color.White, Color(0x14161A33),
    Color(0xFFF0603F), Color(0xFFF0603F), Color(0xFF0E8F7C), Color(0xFFE0405F),
    Color(0xFF0E8F7C), Color(0xFF6F7596), Color(0xFF161A33), Color(0xFF4A5075),
    Accent2 = Color(0xFF6B5CF0), Line = Color(0x1A161A33), Glow = 0.3f, Bare = true,
    Skin = DashSkin.ORBIT, Light = true
)
private val CockpitPalette = DashPalette(
    Color(0xFF17130F), Color.Transparent, Color(0xFF211B16), Color(0xFF2E2620),
    Color(0xFFFF8A1F), Color(0xFFFF8A1F), Color(0xFFFFB347), Color(0xFFFF4A1C),
    Color(0xFF39D353), Color(0xFF8C8074), Color(0xFFE9E1D3), Color(0xFFCBBFAE),
    Accent2 = Color(0xFFFFB347), Line = Color.White.copy(alpha = 0.08f), Glow = 0.5f, Bare = true,
    Skin = DashSkin.COCKPIT
)
// Day cockpit: tan leather, ivory dial faces with black ink, the same chrome.
private val CockpitLightPalette = DashPalette(
    Color(0xFFE3D5C1), Color.Transparent, Color(0xFFF3EADC), Color(0xFFE2D4BF),
    Color(0xFFD9660A), Color(0xFFD9660A), Color(0xFFB9770E), Color(0xFFD23A12),
    Color(0xFF1F9A3A), Color(0xFF7D6E5E), Color(0xFF2A2119), Color(0xFF5C4E40),
    Accent2 = Color(0xFFB9770E), Line = Color(0x1A2A2119), Glow = 0.1f, Bare = true,
    Skin = DashSkin.COCKPIT, Light = true
)
private val HorizonPalette = DashPalette(
    Color(0xFF0A0F2C), Color.Transparent, Color(0xFF1B1537), Color(0x24FFF3E6),
    Color(0xFFFFD6A0), Color(0xFFFFD6A0), Color(0xFF9CF0C0), Color(0xFFFF8F6B),
    Color(0xFF9CF0C0), Color(0x99FFF3E6), Color(0xFFFFF3E6), Color(0xC7FFF3E6),
    Accent2 = Color(0xFFFF8F6B), Line = Color(0x33FFF3E6), Glow = 0.4f, Bare = true,
    Skin = DashSkin.HORIZON
)
// Day horizon: a bright noon scene, navy ink with a pale halo instead of a shadow.
private val HorizonLightPalette = DashPalette(
    Color(0xFFCFE3F5), Color.Transparent, Color(0xFFF8F4EC), Color(0x1A1B2440),
    Color(0xFFB9531A), Color(0xFFB9531A), Color(0xFF1E9C6A), Color(0xFFD9472B),
    Color(0xFF1E9C6A), Color(0x991B2440), Color(0xFF1B2440), Color(0xC71B2440),
    Accent2 = Color(0xFFD9472B), Line = Color(0x331B2440), Glow = 0.2f, Bare = true,
    Skin = DashSkin.HORIZON, Light = true
)
private val TapeDeckPalette = DashPalette(
    Color(0xFF0D0221), Color.Transparent, Color(0xFF1B1230), Color(0xFF2A1F44),
    Color(0xFF05D9E8), Color(0xFF05D9E8), Color(0xFF3CFF8F), Color(0xFFFF2A6D),
    Color(0xFF3CFF8F), Color(0xFF8A7FA8), Color(0xFFEDEDF5), Color(0xFFB9B3CF),
    Accent2 = Color(0xFFFF2A6D), Line = Color(0x59FF2A6D), Glow = 1f, Bare = true,
    Skin = DashSkin.TAPE_DECK
)
// Day tape deck: a pastel Miami-morning sky and a brushed-silver head unit.
private val TapeDeckLightPalette = DashPalette(
    Color(0xFFFDEFF6), Color.Transparent, Color.White, Color(0xFFF1E4F0),
    Color(0xFF00A0B4), Color(0xFF00A0B4), Color(0xFF12B368), Color(0xFFE8175D),
    Color(0xFF12B368), Color(0xFF7A6E92), Color(0xFF2A0F45), Color(0xFF5E4C78),
    Accent2 = Color(0xFFE8175D), Line = Color(0x40E8175D), Glow = 0.4f, Bare = true,
    Skin = DashSkin.TAPE_DECK, Light = true
)

/** How the screen is divided: pages only, or a permanent Google Maps dock beside them. */
enum class DashLayout(val title: String, val description: String) {
    GRID("Dashboards only", "Swipeable pages fill the screen"),
    MAPS_LEFT("Map on the left", "Google Maps docked on the left half, pages swipe on the right"),
    MAPS_RIGHT("Map on the right", "Google Maps docked on the right half, pages swipe on the left")
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

    /** Share of the width the Maps dock takes in the docked layouts (the divider is draggable). */
    const val MIN_DOCK_FRACTION = 0.25f
    const val MAX_DOCK_FRACTION = 0.75f
    private const val KEY_DOCK_FRACTION = "dock_fraction"

    fun loadDockFraction(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_DOCK_FRACTION, 0.5f).coerceIn(MIN_DOCK_FRACTION, MAX_DOCK_FRACTION)

    fun saveDockFraction(context: Context, fraction: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_DOCK_FRACTION, fraction.coerceIn(MIN_DOCK_FRACTION, MAX_DOCK_FRACTION)).apply()
    }
}

object DashThemeStore {
    private const val PREFS = "dashboard_theme"
    private const val KEY = "mode"
    private const val KEY_APPEARANCE = "appearance"

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

    fun loadAppearance(context: Context): DashAppearance = runCatching {
        DashAppearance.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APPEARANCE, DashAppearance.AUTO.name) ?: DashAppearance.AUTO.name
        )
    }.getOrDefault(DashAppearance.AUTO)

    fun saveAppearance(context: Context, appearance: DashAppearance) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APPEARANCE, appearance.name).apply()
    }
}

/** The palette [mode] uses in its dark or [light] version. */
internal fun paletteFor(mode: DashThemeMode, light: Boolean): DashPalette = when (mode) {
    DashThemeMode.AUTO -> if (light) AutoLightPalette else AutoDarkPalette
    DashThemeMode.ORIGINAL -> if (light) OriginalLightPalette else OriginalDarkPalette
    DashThemeMode.AURORA -> if (light) AuroraLightPalette else AuroraPalette
    DashThemeMode.NEON_DARK -> if (light) NeonLightPalette else NeonDarkPalette
    DashThemeMode.CLEAN_LIGHT -> if (light) CleanLightPalette else CleanDarkPalette
    DashThemeMode.DARK_GLASS -> if (light) FrostedGlassPalette else DarkGlassPalette
    DashThemeMode.SPORTY -> if (light) SportyLightPalette else SportyPalette
    DashThemeMode.FLOATING -> if (light) FloatingLightPalette else FloatingPalette
    DashThemeMode.ORBIT -> if (light) OrbitLightPalette else OrbitPalette
    DashThemeMode.COCKPIT -> if (light) CockpitLightPalette else CockpitPalette
    DashThemeMode.HORIZON -> if (light) HorizonLightPalette else HorizonPalette
    DashThemeMode.TAPE_DECK -> if (light) TapeDeckLightPalette else TapeDeckPalette
}

/** True when [this] appearance shows the light version right now. */
@Composable
internal fun DashAppearance.isLight(): Boolean = when (this) {
    DashAppearance.AUTO -> !isSystemInDarkTheme()
    DashAppearance.DARK -> false
    DashAppearance.LIGHT -> true
}

object DashColors {
    private var current by mutableStateOf(AutoDarkPalette)

    @Composable
    fun Sync(mode: DashThemeMode, appearance: DashAppearance) {
        val target = paletteFor(mode, appearance.isLight())
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
    val Bare get() = current.Bare
    val BackgroundStops get() = current.BackgroundStops
    val Skin get() = current.Skin
    val Light get() = current.Light

    /** Diagonal accent → accent2 gradient for primary controls. */
    val AccentBrush: Brush get() = Brush.linearGradient(listOf(current.Accent, current.Accent2))

    /** Text colour that reads on top of [AccentBrush]. Light themes use deep accents, so white. */
    val OnAccent: Color get() = when {
        current.Light -> Color.White
        current.Glass || current.Glow > 0f -> Color(0xFF03111F)
        else -> current.Background
    }

    /** Brightest ink, for glowing numerals: white at night, the text colour by day. */
    val Bright: Color get() = if (current.Light) current.TextPrimary else Color.White

    /** Translucent lift behind an item on glass (chip, row, gauge track): a white haze at night, a faint ink tint by day. */
    fun haze(alpha: Float): Color =
        if (current.Light) Color.Black.copy(alpha = alpha * 0.7f) else Color.White.copy(alpha = alpha)

    /** Translucent well sunk into glass (progress and slider tracks): deep at night, faint by day. */
    fun well(alpha: Float): Color = Color.Black.copy(alpha = if (current.Light) alpha * 0.25f else alpha)
}
