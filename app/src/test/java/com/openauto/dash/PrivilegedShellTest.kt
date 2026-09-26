package com.openauto.dash

import com.openauto.dash.PrivilegedShell.Access
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each kind of access opens up: root does everything, ADB the shell-only features, nothing else. */
class PrivilegedShellTest {

    @Test
    fun rootAndAdbAreShells_rootAloneIsRoot() {
        assertTrue(Access.ROOT.shell); assertTrue(Access.ROOT.root)
        assertTrue(Access.ADB.shell); assertFalse(Access.ADB.root)
        assertFalse(Access.NONE.shell); assertFalse(Access.NONE.root)
        assertFalse(Access.UNKNOWN.shell); assertFalse(Access.UNKNOWN.root)
    }

    @Test
    fun theCanboxTilesNeedRoot_theMapsWindowAShell_theRestNothing() {
        for (kind in listOf(BuiltinKind.DOORS, BuiltinKind.CAN_MON)) {
            assertEquals(kind.name, listOf(Access.ROOT), Access.entries.filter { it.allows(kind) })
        }
        // The Maps window, and the car box's data (shared once registered through the shell).
        for (kind in listOf(BuiltinKind.PIP_ANCHOR, BuiltinKind.CAR_STATUS)) {
            assertEquals(kind.name, setOf(Access.ROOT, Access.ADB), Access.entries.filter { it.allows(kind) }.toSet())
        }
        val plain = BuiltinKind.entries - setOf(BuiltinKind.DOORS, BuiltinKind.CAN_MON, BuiltinKind.PIP_ANCHOR, BuiltinKind.CAR_STATUS)
        for (kind in plain) for (access in Access.entries) assertTrue("$kind under $access", access.allows(kind))
    }

    @Test
    fun untilProbed_nothingIsOffered() {
        assertEquals(Access.UNKNOWN, PrivilegedShell.access.value)
        assertFalse(PrivilegedShell.access.value.shell)
    }
}
