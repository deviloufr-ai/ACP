package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The built-in key's lock. The real activation code is deliberately not in the repo. */
class AiKeyVaultTest {

    @Test
    fun theRightCodeOpensWhatWasSealed() {
        val sealed = AiKeyVault.seal("AQ.sample-key", "ZX 42")
        assertEquals("AQ.sample-key", AiKeyVault.open(sealed, "zx42"))
    }

    @Test
    fun aWrongCodeOpensNothing() {
        val sealed = AiKeyVault.seal("AQ.sample-key", "ZX42")
        assertNull(AiKeyVault.open(sealed, "ZX43"))
        assertNull(AiKeyVault.open(sealed, ""))
    }

    @Test
    fun sealingTwiceNeverRepeatsTheCiphertext() {
        assertNotEquals(AiKeyVault.seal("AQ.sample-key", "ZX42"), AiKeyVault.seal("AQ.sample-key", "ZX42"))
    }

    @Test
    fun aTamperedCiphertextIsRejected() {
        val bytes = Base64.getDecoder().decode(AiKeyVault.seal("AQ.sample-key", "ZX42"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(AiKeyVault.open(Base64.getEncoder().encodeToString(bytes), "ZX42"))
        assertNull(AiKeyVault.open("not base64 at all", "ZX42"))
    }

    @Test
    fun theBuiltInKeyShipsLocked() {
        assertTrue(AiKeyVault.available)
        assertNull(AiKeyVault.unlock("WRONG1"))
    }
}
