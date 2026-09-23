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

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
