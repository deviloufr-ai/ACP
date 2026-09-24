package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Per-tile designs. Every built-in widget can be drawn in any [WidgetDesign]:
 * [WidgetDesign.STANDARD] is the widget's own renderer, every other design
 * draws the widget's live [WidgetFace] with one [FaceLayout] in one
 * [FaceLookKind] material. The Map, Maps window and 3D car keep their live
 * view and get the design's frame around it instead.
 */

/** How a design arranges a widget's reading. */
internal enum class FaceLayout { HERO, ARC, RING, BARS, STATS, TERMINAL, DIAL, FLAP }

/** The material a design is drawn in. THEME and MINIMAL follow the dashboard theme; the rest bring their own colours. */
internal enum class FaceLookKind { THEME, MINIMAL, LCD, AMBER, NEON, PAPER, GLASS, CARBON, BLUEPRINT, CHROME, FLAP }

/**
 * A tile's design. Names are persisted with the tile, so never rename an
 * entry; new designs go at the end. [layout] is null for [STANDARD].
 */
enum class WidgetDesign(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    internal val layout: FaceLayout?,
    internal val look: FaceLookKind
) {
    STANDARD(R.string.design_standard, R.string.design_standard_desc, null, FaceLookKind.THEME),
    HERO(R.string.design_hero, R.string.design_hero_desc, FaceLayout.HERO, FaceLookKind.THEME),
    GAUGE(R.string.design_gauge, R.string.design_gauge_desc, FaceLayout.ARC, FaceLookKind.THEME),
    RING(R.string.design_ring, R.string.design_ring_desc, FaceLayout.RING, FaceLookKind.THEME),
    BARS(R.string.design_bars, R.string.design_bars_desc, FaceLayout.BARS, FaceLookKind.THEME),
    STATS(R.string.design_stats, R.string.design_stats_desc, FaceLayout.STATS, FaceLookKind.THEME),
    MINIMAL(R.string.design_minimal, R.string.design_minimal_desc, FaceLayout.HERO, FaceLookKind.MINIMAL),
    LCD(R.string.design_lcd, R.string.design_lcd_desc, FaceLayout.HERO, FaceLookKind.LCD),
    AMBER(R.string.design_amber, R.string.design_amber_desc, FaceLayout.TERMINAL, FaceLookKind.AMBER),
    NEON(R.string.design_neon, R.string.design_neon_desc, FaceLayout.ARC, FaceLookKind.NEON),
    PAPER(R.string.design_paper, R.string.design_paper_desc, FaceLayout.STATS, FaceLookKind.PAPER),
    GLASS(R.string.design_glass, R.string.design_glass_desc, FaceLayout.RING, FaceLookKind.GLASS),
    CARBON(R.string.design_carbon, R.string.design_carbon_desc, FaceLayout.BARS, FaceLookKind.CARBON),
    BLUEPRINT(R.string.design_blueprint, R.string.design_blueprint_desc, FaceLayout.DIAL, FaceLookKind.BLUEPRINT),
    CHRONO(R.string.design_chrono, R.string.design_chrono_desc, FaceLayout.DIAL, FaceLookKind.CHROME),
    FLAP(R.string.design_flap, R.string.design_flap_desc, FaceLayout.FLAP, FaceLookKind.FLAP);

    companion object {
        /** The saved design, or [STANDARD] for a blank or unknown name (a newer build's design after a downgrade). */
        fun fromName(name: String?): WidgetDesign =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/** The design's display name in the current language (the enum name is what gets saved). */
internal val WidgetDesign.title: String
    @Composable get() = stringResource(titleRes)

internal val WidgetDesign.description: String
    @Composable get() = stringResource(descriptionRes)

// --- What a design draws -----------------------------------------------------

internal class FaceStat(val label: String, val value: String)

internal class FaceRow(
    val title: String,
    val detail: String,
    val alert: Boolean = false,
    /** Initials drawn in a round badge instead of the row's dot (contacts). */
    val badge: String? = null,
    val onClick: (() -> Unit)? = null
)

internal class FaceAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val enabled: Boolean = true
)

/**
 * One widget's reading, in the shape every design can draw: a headline
 * [value] (+ [unit]) with a [caption], an optional 0..1 [fraction] for
 * gauges and bars, secondary [stats], list [rows] and [actions].
 *
 * [textValue] marks a word rather than a number (a song, a contact), which
 * designs set smaller. [fullCircle] makes gauges run the whole way round
 * (compass, clock seconds). [clock] (h, m, s) and [compass] let the dial
 * designs draw hands or cardinal letters.
 */
internal class WidgetFace(
    val icon: ImageVector,
    val title: String,
    val value: String,
    val unit: String = "",
    val caption: String = "",
    val fraction: Float? = null,
    val stats: List<FaceStat> = emptyList(),
    val rows: List<FaceRow> = emptyList(),
    val actions: List<FaceAction> = emptyList(),
    val alert: Boolean = false,
    val textValue: Boolean = false,
    val fullCircle: Boolean = false,
    val art: ImageBitmap? = null,
    val clock: Triple<Int, Int, Int>? = null,
    val compass: Boolean = false,
    val onClick: (() -> Unit)? = null
)

// --- Materials ----------------------------------------------------------------

/** Extra drawing a material adds on top of its colours. */
internal enum class LookDecoration { NONE, SCANLINES, CARBON, BLUEPRINT, CHROME, NEON, GLASS }

/**
 * Colours, type and shape of one material. [background] null means the
 * theme's own [Card]; a transparent brush means no card at all (Minimal).
 */
internal class FaceLook(
    val kind: FaceLookKind,
    val background: Brush?,
    val ink: Color,
    val dim: Color,
    val accent: Color,
    val accent2: Color,
    val warn: Color,
    val track: Color,
    /** Fill behind rows, chips, cells and buttons. */
    val fill: Color,
    val onAccent: Color,
    val border: Color?,
    val borderWidth: Dp = 1.dp,
    val radius: Dp,
    val font: FontFamily,
    val numFont: FontFamily,
    val numWeight: FontWeight,
    val numItalic: Boolean = false,
    val labelWeight: FontWeight = FontWeight.Bold,
    /** Halo behind numerals and lit segments, or null for none. */
    val glow: Color? = null,
    /** Unlit segments behind LCD digits ("88:88"). */
    val ghost: Color? = null,
    val decoration: LookDecoration = LookDecoration.NONE,
    /** Square-ish buttons and chips instead of pills. */
    val squareControls: Boolean = false
)

private val Mono = FontFamily.Monospace
private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

/** The material for [kind]; THEME and MINIMAL read the live dashboard palette. */
internal fun faceLook(kind: FaceLookKind): FaceLook = when (kind) {
    FaceLookKind.THEME -> FaceLook(
        kind, background = null,
        ink = DashColors.TextPrimary, dim = DashColors.TextSecondary, accent = DashColors.Accent, accent2 = DashColors.Accent2,
        warn = DashColors.Warning, track = DashColors.haze(0.10f),
        fill = if (DashColors.Glass) DashColors.haze(0.07f) else DashColors.CardHi, onAccent = DashColors.OnAccent,
        border = null, radius = 24.dp, font = Sans, numFont = Sans, numWeight = FontWeight.Bold,
        glow = if (DashColors.Glow > 0f) DashColors.Accent.copy(alpha = 0.35f * DashColors.Glow) else null
    )
    FaceLookKind.MINIMAL -> FaceLook(
        kind, background = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
        ink = DashColors.TextPrimary, dim = DashColors.Muted, accent = DashColors.Accent, accent2 = DashColors.Accent2,
        warn = DashColors.Warning, track = DashColors.TextPrimary.copy(alpha = 0.10f), fill = Color.Transparent,
        onAccent = DashColors.OnAccent, border = null, radius = 0.dp, font = Sans, numFont = Sans,
        numWeight = FontWeight.Thin, labelWeight = FontWeight.Normal
    )
    FaceLookKind.LCD -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF122010), Color(0xFF0B1509))),
        ink = Color(0xFFA6FF6E), dim = Color(0xFF5E9A3C), accent = Color(0xFFA6FF6E), accent2 = Color(0xFFD8FF9E),
        warn = Color(0xFFFFD84A), track = Color(0x1AA6FF6E), fill = Color(0x14A6FF6E), onAccent = Color(0xFF0E1A0B),
        border = Color(0xFF1F2B1A), borderWidth = 3.dp, radius = 10.dp, font = Mono, numFont = Mono,
        numWeight = FontWeight.Normal, labelWeight = FontWeight.Normal, glow = Color(0x99A6FF6E),
        ghost = Color(0x14A6FF6E), decoration = LookDecoration.SCANLINES, squareControls = true
    )
    FaceLookKind.AMBER -> FaceLook(
        kind, background = Brush.radialGradient(listOf(Color(0xFF1A1206), Color(0xFF070503))),
        ink = Color(0xFFFFB23F), dim = Color(0xFF9A6A22), accent = Color(0xFFFFB23F), accent2 = Color(0xFFFFD27F),
        warn = Color(0xFFFF5A36), track = Color(0x1FFFB23F), fill = Color.Transparent, onAccent = Color(0xFF070503),
        border = Color(0xFF3A2A12), radius = 8.dp, font = Mono, numFont = Mono, numWeight = FontWeight.SemiBold,
        glow = Color(0x88FFB23F), decoration = LookDecoration.SCANLINES, squareControls = true
    )
    FaceLookKind.NEON -> FaceLook(
        kind, background = Brush.radialGradient(listOf(Color(0xFF2A0A3A), Color(0xFF07030F))),
        ink = Color(0xFFF6F0FF), dim = Color(0xFFA99BC9), accent = Color(0xFFFF2BD6), accent2 = Color(0xFF25F4EE),
        warn = Color(0xFFFF4D6D), track = Color(0x14FFFFFF), fill = Color(0x0AFFFFFF), onAccent = Color(0xFF07030F),
        border = Color(0xFFFF2BD6), borderWidth = 1.5.dp, radius = 20.dp, font = Sans, numFont = CondensedFamily,
        numWeight = FontWeight.Bold, glow = Color(0xFFFF2BD6), decoration = LookDecoration.NEON
    )
    FaceLookKind.PAPER -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFFE6E6E0), Color(0xFFDFDFD8))),
        ink = Color(0xFF151515), dim = Color(0xFF5C5C57), accent = Color(0xFF151515), accent2 = Color(0xFF151515),
        warn = Color(0xFF151515), track = Color(0x1F151515), fill = Color.Transparent, onAccent = Color(0xFFE3E3DD),
        border = Color(0xFFC9C9C1), radius = 6.dp, font = Serif, numFont = Serif, numWeight = FontWeight.SemiBold,
        squareControls = true
    )
    FaceLookKind.GLASS -> FaceLook(
        kind, background = Brush.linearGradient(listOf(Color(0xFF1A2748), Color(0xFF0C1226))),
        ink = Color.White, dim = Color(0xFFC3CDE6), accent = Color(0xFF7FE3FF), accent2 = Color(0xFFB7A2FF),
        warn = Color(0xFFFF8FA3), track = Color(0x22FFFFFF), fill = Color(0x1FFFFFFF), onAccent = Color(0xFF0C1226),
        border = Color(0x30FFFFFF), radius = 26.dp, font = Sans, numFont = Sans, numWeight = FontWeight.Light,
        labelWeight = FontWeight.Medium, decoration = LookDecoration.GLASS
    )
    FaceLookKind.CARBON -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF151515), Color(0xFF0F0F0F))),
        ink = Color(0xFFF4F4F4), dim = Color(0xFF9A9A9A), accent = Color(0xFFFF2E3F), accent2 = Color(0xFFFF8A3D),
        warn = Color(0xFFFFD23F), track = Color(0x14FFFFFF), fill = Color(0x0FFFFFFF), onAccent = Color.White,
        border = Color(0xFF2A2A2A), radius = 12.dp, font = CondensedFamily, numFont = CondensedFamily,
        numWeight = FontWeight.ExtraBold, numItalic = true, decoration = LookDecoration.CARBON, squareControls = true
    )
    FaceLookKind.BLUEPRINT -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF123E78), Color(0xFF0F3666))),
        ink = Color(0xFFF2F7FF), dim = Color(0xFFA9C4EA), accent = Color.White, accent2 = Color(0xFF9FD0FF),
        warn = Color(0xFFFFC94A), track = Color(0x26FFFFFF), fill = Color.Transparent, onAccent = Color(0xFF123E78),
        border = Color(0x55FFFFFF), radius = 4.dp, font = Mono, numFont = Mono, numWeight = FontWeight.Medium,
        labelWeight = FontWeight.Medium, decoration = LookDecoration.BLUEPRINT, squareControls = true
    )
    FaceLookKind.CHROME -> FaceLook(
        kind, background = Brush.radialGradient(listOf(Color(0xFF2A2F36), Color(0xFF121417))),
        ink = Color(0xFFF3F3F3), dim = Color(0xFF9CA3AD), accent = Color(0xFFFF6A2B), accent2 = Color(0xFFFFB36B),
        warn = Color(0xFFFF3B30), track = Color(0x1AFFFFFF), fill = Color(0x0FFFFFFF), onAccent = Color(0xFF121417),
        border = Color(0xFFB9BFC7), borderWidth = 4.dp, radius = 24.dp, font = CondensedFamily, numFont = CondensedFamily,
        numWeight = FontWeight.Bold, decoration = LookDecoration.CHROME
    )
    FaceLookKind.FLAP -> FaceLook(
        kind, background = Brush.verticalGradient(listOf(Color(0xFF111111), Color(0xFF0B0B0B))),
        ink = Color(0xFFF2F2F2), dim = Color(0xFF8D8D8D), accent = Color(0xFFFFD23F), accent2 = Color(0xFFFFE58A),
        warn = Color(0xFFFF5A4E), track = Color(0x14FFFFFF), fill = Color(0xFF1C1C1C), onAccent = Color(0xFF0D0D0D),
        border = Color(0xFF262626), radius = 10.dp, font = CondensedFamily, numFont = Mono, numWeight = FontWeight.Bold,
        squareControls = true
    )
}
