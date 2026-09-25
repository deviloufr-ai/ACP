package com.openauto.dash.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingOfferTest {

    @Test
    fun roundTripsThroughItsUri() {
        val offer = PairingOffer.create("C4 Picasso — Autoradio & co")
        val back = PairingOffer.parse(offer.toUri())!!
        assertEquals(offer.id, back.id)
        assertEquals(offer.unitName, back.unitName)
        assertArrayEquals(offer.secret, back.secret)
    }

    @Test
    fun refusesAnythingElse() {
        val good = PairingOffer.create("Unit").toUri()
        assertNull(PairingOffer.parse("https://example.com/?v=1"))
        assertNull(PairingOffer.parse(good.replace("v=1", "v=2")))
        assertNull(PairingOffer.parse(good.replace(Regex("k=[^&]+"), "k=AAAA")))
        assertNull(PairingOffer.parse(good.replace(Regex("id=[^&]+"), "id=xyz")))
        assertNull(PairingOffer.parse(good.replace(Regex("&n=[^&]+"), "")))
        assertNull(PairingOffer.parse("not a uri at all %%"))
    }
}
