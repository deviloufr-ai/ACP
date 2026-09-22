package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** `am stack list` parsing for the pinned (picture-in-picture) stack. */
class PipAnchorTest {

    private val android10 = """
        Stack id=1 bounds=[0,0][1280,720] displayId=0 userId=0
         configuration={1.0 ?mcc?mnc [fr_FR] ldltr sw720dp w1280dp h672dp 160dpi lrg long land finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1280, 720) mAppBounds=Rect(0, 0 - 1280, 672) mWindowingMode=fullscreen mActivityType=standard} s.12}
          taskId=41: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true topActivity=ComponentInfo{com.openauto.dash/com.openauto.dash.MainActivity}
        Stack id=3 bounds=[960,420][1264,608] displayId=0 userId=0
         configuration={1.0 ?mcc?mnc [fr_FR] ldltr sw720dp w1280dp h672dp 160dpi lrg long land finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(960, 420 - 1264, 608) mAppBounds=Rect(960, 420 - 1264, 608) mWindowingMode=pinned mActivityType=standard} s.6}
          taskId=57: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[960,420][1264,608] userId=0 visible=true topActivity=ComponentInfo{com.google.android.apps.maps/com.google.android.maps.MapsActivity}
        Stack id=0 bounds=[0,0][1280,720] displayId=0 userId=0
         configuration={... mWindowingMode=fullscreen mActivityType=home ...}
          taskId=2: com.android.launcher3/.Launcher bounds=[0,0][1280,720] userId=0 visible=false
    """.trimIndent()

    @Test
    fun findsThePinnedStackAndItsPackage() {
        val pinned = PipAnchor.parsePinnedStack(android10)
        assertNotNull(pinned)
        assertEquals(3, pinned!!.stackId)
        assertEquals("com.google.android.apps.maps", pinned.packageName)
        assertEquals(PipAnchor.ScreenRect(960, 420, 1264, 608), pinned.bounds)
    }

    @Test
    fun noPinnedStackMeansNoPip() {
        val without = android10.lines().filterNot { it.contains("Stack id=3") || it.contains("pinned") || it.contains("taskId=57") }
            .joinToString("\n")
        assertNull(PipAnchor.parsePinnedStack(without))
        assertNull(PipAnchor.parsePinnedStack(""))
        assertNull(PipAnchor.parsePinnedStack("Error: no such command"))
    }

    @Test
    fun pinnedStackWithoutATaskIsIgnored() {
        val empty = """
            Stack id=3 bounds=[960,420][1264,608] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=pinned mActivityType=standard} }
        """.trimIndent()
        assertNull(PipAnchor.parsePinnedStack(empty))
    }
}
