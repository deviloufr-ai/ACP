package com.openauto.dash.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredPairingTest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun roundTripsEveryField() {
        val stored = StoredPairing("0123456789abcdef", secret, "Pixel", 1_700_000_000_000, forgotten = true, refusals = 2, lastRefusalAt = 5)
        val back = PairingStorage.decode(PairingStorage.encode(listOf(stored))).single()
        assertEquals(stored.id, back.id)
        assertArrayEquals(secret, back.secret)
        assertEquals("Pixel", back.name)
        assertEquals(1_700_000_000_000, back.pairedAt)
        assertTrue(back.forgotten)
        assertEquals(2, back.refusals)
        assertEquals(5L, back.lastRefusalAt)
    }

    @Test
    fun readsWhatTheAppsStoredBefore() {
        // The launcher's and the companion's format until now (org.json, standard base64).
        val secretB64 = java.util.Base64.getEncoder().encodeToString(secret)
        val launcher = """[{"id":"aaaaaaaaaaaaaaaa","secret":"$secretB64","name":"","pairedAt":12,"forgotten":false}]"""
        val companion = """[{"id":"bbbbbbbbbbbbbbbb","name":"C4 Picasso","secret":"$secretB64","pairedAt":34}]"""
        val a = PairingStorage.decode(launcher).single()
        assertEquals("aaaaaaaaaaaaaaaa", a.id)
        assertArrayEquals(secret, a.secret)
        assertFalse(a.forgotten)
        assertEquals(0, a.refusals)
        val b = PairingStorage.decode(companion).single()
        assertEquals("C4 Picasso", b.name)
        assertEquals(34L, b.pairedAt)
    }

    @Test
    fun aDamagedEntryDoesNotLoseTheOthers() {
        val good = PairingStorage.encode(listOf(StoredPairing("cccccccccccccccc", secret)))
        val raw = good.removeSuffix("]") + """,{"id":"dddddddddddddddd","secret":"not base64!"},{"name":"no id"}]"""
        assertEquals(listOf("cccccccccccccccc"), PairingStorage.decode(raw).map { it.id })
        assertTrue(PairingStorage.decode("not json").isEmpty())
        assertTrue(PairingStorage.decode(null).isEmpty())
        assertTrue(PairingStorage.decode("{}").isEmpty())
    }
}
