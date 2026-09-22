package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Release tag -> build number, the comparison the in-app updater relies on. */
class UpdateManagerTest {

    @Test
    fun lastNumberInTheTagIsTheBuild() {
        assertEquals(42L, UpdateManager.parseBuildNumber("v1.0.42"))
        assertEquals(119L, UpdateManager.parseBuildNumber("Dashwheel v1.0.119"))
        assertEquals(7L, UpdateManager.parseBuildNumber("7"))
    }

    @Test
    fun tagWithoutBuildSuffixReadsAsZero() {
        // "v1.0" means build 0, which is older than every CI build, so the
        // updater offers the real release rather than staying silent.
        assertEquals(0L, UpdateManager.parseBuildNumber("v1.0"))
    }

    @Test
    fun noDigitsMeansUnknown() {
        assertNull(UpdateManager.parseBuildNumber("nightly"))
        assertNull(UpdateManager.parseBuildNumber(""))
    }
}
