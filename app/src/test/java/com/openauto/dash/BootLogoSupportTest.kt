package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Test

class BootLogoSupportTest {
    @Test
    fun `boot logo is not offered anywhere but the QF firmware`() {
        // No ro.qf.platform, no update_bootlogo.sh here: the menu entry must stay hidden.
        assertFalse(BootLogoSupport.available)
    }
}
