package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The learning screen's monitor: raw input lines made short enough to read. */
class WheelMonitorTest {

    @Test
    fun geteventLinesKeepTheKeyTheStateAndTheDevice() {
        assertEquals(
            "KEY_NEXTSONG DOWN (event2)",
            WheelMonitor.tidyGetevent("/dev/input/event2: EV_KEY       KEY_NEXTSONG         DOWN")
        )
    }

    @Test
    fun geteventLinesWithoutADeviceKeepTheKeyAndTheState() {
        assertEquals("KEY_VOLUMEUP UP", WheelMonitor.tidyGetevent("EV_KEY       KEY_VOLUMEUP         UP"))
    }
}
