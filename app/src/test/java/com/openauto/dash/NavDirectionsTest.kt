package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turn-by-turn notification text as Google Maps and Waze lay it out. */
class NavDirectionsTest {

    @Test
    fun mapsStyleSeparateLines() {
        val s = NavDirections.fromLines(
            listOf("Turn right onto Main St", "300 m", "12 min · 6.4 km · 09:48"),
            icon = null, packageName = "com.google.android.apps.maps"
        )
        assertNotNull(s)
        assertTrue(s!!.active)
        assertEquals("Turn right onto Main St", s.instruction)
        assertEquals("300 m", s.distance)
        assertEquals("12 min · 6.4 km · 09:48", s.eta)
        assertEquals("300" to "m", s.distanceParts)
        assertEquals(listOf("12 min", "6.4 km", "09:48"), s.etaParts)
        assertEquals("com.google.android.apps.maps", s.packageName)
    }

    @Test
    fun distanceEmbeddedInTheInstructionIsSplitOut() {
        val s = NavDirections.fromLines(
            listOf("In 300 m, turn right", "12 min · 6.4 km"),
            icon = null, packageName = "com.waze"
        )!!
        assertEquals("300 m", s.distance)
        assertEquals("Turn right", s.instruction)
    }

    @Test
    fun decimalKilometresAndImperialUnits() {
        assertEquals("1.2" to "km", NavState(distance = "1.2 km").distanceParts)
        assertEquals("1,2" to "km", NavState(distance = "1,2 km").distanceParts)
        assertEquals("500" to "ft", NavState(distance = "500 ft").distanceParts)
        assertEquals("soon" to "", NavState(distance = "soon").distanceParts)
    }

    @Test
    fun distanceOnlyMeansContinue() {
        val s = NavDirections.fromLines(listOf("2.5 km"), icon = null, packageName = "com.waze")!!
        assertEquals("Continue", s.instruction)
        assertEquals("2.5 km", s.distance)
    }

    @Test
    fun plainStatusNotificationsAreNotDirections() {
        assertNull(NavDirections.fromLines(listOf("Google Maps is running"), null, "com.google.android.apps.maps"))
        assertNull(NavDirections.fromLines(emptyList(), null, "com.waze"))
    }
}
