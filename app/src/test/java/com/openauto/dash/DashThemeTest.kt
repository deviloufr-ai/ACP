package com.openauto.dash

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every theme's dark and light versions. */
class DashThemeTest {

    @Test
    fun everyThemeHasADarkAndALightVersion() {
        DashThemeMode.entries.forEach { mode ->
            assertFalse("$mode dark", paletteFor(mode, light = false).Light)
            assertTrue("$mode light", paletteFor(mode, light = true).Light)
        }
    }

    @Test
    fun bothVersionsKeepTheThemesDesign() {
        DashThemeMode.entries.forEach { mode ->
            val dark = paletteFor(mode, light = false)
            val light = paletteFor(mode, light = true)
            assertEquals("$mode skin", dark.Skin, light.Skin)
            assertEquals("$mode glass", dark.Glass, light.Glass)
            assertEquals("$mode bare", dark.Bare, light.Bare)
            assertEquals("$mode original", dark.Original, light.Original)
        }
    }

    @Test
    fun textReadsOnThePageInBothVersions() {
        DashThemeMode.entries.forEach { mode ->
            listOf(false, true).forEach { light ->
                val p = paletteFor(mode, light)
                val page = p.Background
                val ratio = contrast(p.TextPrimary.compositeOver(page), page)
                assertTrue("$mode light=$light contrast $ratio", ratio >= 7f)
                // Light versions put dark ink on a pale page, and the other way round.
                assertEquals("$mode light=$light page", light, page.luminance() > 0.5f)
            }
        }
    }

    @Test
    fun alertColoursReadTheSameWayInEveryTheme() {
        DashThemeMode.entries.forEach { mode ->
            listOf(false, true).forEach { light ->
                val p = paletteFor(mode, light)
                val page = p.Background
                // Amber and red are different colours, whatever the theme.
                assertTrue("$mode light=$light warning vs critical", colourDistance(p.Warning, p.Critical) > 0.25f)
                // Both, and the tachometer, read on the page (WCAG 3:1 for large text and graphics).
                listOf("warning" to p.Warning, "critical" to p.Critical, "tacho" to p.Tacho).forEach { (name, c) ->
                    assertTrue("$mode light=$light $name contrast", contrast(c.compositeOver(page), page) >= 3f)
                }
                // Amber is amber: more red than blue, and clearly some green.
                assertTrue("$mode light=$light amber hue", p.Tacho.red > p.Tacho.blue && p.Tacho.green > p.Tacho.blue)
                // Red is red: more red than green and blue.
                assertTrue("$mode light=$light red hue", p.Critical.red > p.Critical.green && p.Critical.red > p.Critical.blue)
            }
        }
    }

    private fun colourDistance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
