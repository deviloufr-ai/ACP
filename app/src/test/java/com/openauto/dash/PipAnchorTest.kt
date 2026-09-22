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
        val pinned = PipAnchor.parseFloatingWindow(android10)
        assertNotNull(pinned)
        assertEquals(3, pinned!!.stackId)
        assertEquals("com.google.android.apps.maps", pinned.packageName)
        assertEquals(PipAnchor.ScreenRect(960, 420, 1264, 608), pinned.bounds)
    }

    @Test
    fun noPinnedStackMeansNoPip() {
        val without = android10.lines().filterNot { it.contains("Stack id=3") || it.contains("pinned") || it.contains("taskId=57") }
            .joinToString("\n")
        assertNull(PipAnchor.parseFloatingWindow(without))
        assertNull(PipAnchor.parseFloatingWindow(""))
        assertNull(PipAnchor.parseFloatingWindow("Error: no such command"))
    }

    @Test
    fun freeformWindowIsFoundWhenThereIsNoPip() {
        val freeform = """
            Stack id=1 bounds=[0,0][1280,720] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=fullscreen mActivityType=standard} }
              taskId=41: com.openauto.dash/com.openauto.dash.MainActivity bounds=[0,0][1280,720] userId=0 visible=true
            Stack id=7 bounds=[640,80][1240,660] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=freeform mActivityType=standard} }
              taskId=63: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[640,80][1240,660] userId=0 visible=true
        """.trimIndent()
        val win = PipAnchor.parseFloatingWindow(freeform)!!
        assertEquals("freeform", win.mode)
        assertEquals(7, win.stackId)
        assertEquals(63, win.taskId)
        assertEquals("com.google.android.apps.maps", win.packageName)
        assertEquals("fullscreen dash \u00b7 freeform maps", PipAnchor.summarizeStacks(freeform))
    }

    @Test
    fun numericWindowingModeIsUnderstood() {
        val numeric = """
            Stack id=4 bounds=[0,0][600,400] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=5 mActivityType=standard} }
              taskId=9: com.waze/com.waze.MainActivity bounds=[0,0][600,400] userId=0 visible=true
        """.trimIndent()
        assertEquals("freeform", PipAnchor.parseFloatingWindow(numeric)!!.mode)
    }

    @Test
    fun pinnedStackWithoutATaskIsIgnored() {
        val empty = """
            Stack id=3 bounds=[960,420][1264,608] displayId=0 userId=0
             configuration={ winConfig={ mWindowingMode=pinned mActivityType=standard} }
        """.trimIndent()
        assertNull(PipAnchor.parseFloatingWindow(empty))
    }

    @Test
    fun closeEnoughAllowsSystemAspectAdjustments() {
        val target = PipAnchor.ScreenRect(400, 100, 900, 400)
        assertEquals(true, PipAnchor.isClose(PipAnchor.ScreenRect(400, 100, 900, 400), target))
        // Same centre, width shrunk by the aspect-ratio rule: still docked.
        assertEquals(true, PipAnchor.isClose(PipAnchor.ScreenRect(450, 120, 850, 380), target))
        // Parked in a corner: not docked.
        assertEquals(false, PipAnchor.isClose(PipAnchor.ScreenRect(960, 420, 1264, 608), target))
        // Centre inside but a quarter of the size: not docked.
        assertEquals(false, PipAnchor.isClose(PipAnchor.ScreenRect(600, 200, 700, 300), target))
    }
}
