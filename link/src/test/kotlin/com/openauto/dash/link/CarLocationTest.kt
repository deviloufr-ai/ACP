package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLocationTest {
    @Test
    fun roundTripsThroughTheCodec() {
        val sent = CarLocation(lat = 48.8566, lng = 2.3522, at = 1_790_000_000_000L, saved = true)
        assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
    }

    @Test
    fun anOlderPeerSkipsItInsteadOfDroppingTheLink() {
        // What an older companion sees: a message type it doesn't know.
        assertNull(LinkCodec.decode("""{"t":"car_location_v2","lat":1.0}""".encodeToByteArray()))
    }
}
