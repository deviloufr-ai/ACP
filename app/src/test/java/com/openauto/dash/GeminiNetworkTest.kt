package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** Reaching Gemini from a head unit: address order and what a failure is called. */
class GeminiNetworkTest {

    private fun v4(last: Int) = InetAddress.getByAddress(byteArrayOf(142.toByte(), 250.toByte(), 1, last.toByte()))
    private fun v6(last: Int) = InetAddress.getByAddress(ByteArray(15) { if (it == 0) 0x2a else 0 } + last.toByte())

    @Test
    fun ipv4AddressesAreTriedFirstInTheirOriginalOrder() {
        val a = v6(1)
        val b = v4(2)
        val c = v6(3)
        val d = v4(4)
        assertEquals(listOf(b, d, a, c), Ipv4First.preferIpv4(listOf(a, b, c, d)))
    }

    @Test
    fun eachNetworkFailureGetsItsOwnExplanation() {
        fun res(e: IOException) = AiMechanic.networkErrorRes(e)
        assertEquals(R.string.ai_error_no_dns, res(UnknownHostException("generativelanguage.googleapis.com")))
        assertEquals(R.string.ai_error_no_access, res(NoInternetAccessException(200)))
        assertEquals(R.string.ai_error_tls, res(SSLHandshakeException("certificate not yet valid")))
        assertEquals(R.string.ai_error_timeout, res(SocketTimeoutException("timeout")))
        assertEquals(R.string.ai_error_timeout, res(InterruptedIOException("timeout")))
        assertEquals(R.string.ai_error_connect, res(ConnectException("failed to connect")))
        assertEquals(R.string.ai_error_offline, res(IOException("stream closed")))
    }

    @Test
    fun whenEveryModelFailsTheMostUsefulReasonIsShown() {
        val busy = GeminiException("high demand", 503)
        val retired = GeminiException("no longer available", 404)
        val quota = GeminiException("quota", 429)
        val badKey = GeminiException("API key not valid", 400)
        val offline = UnknownHostException("generativelanguage.googleapis.com")
        assertEquals(badKey, GeminiClient.mostTelling(listOf(busy, retired, badKey, quota)))
        assertEquals(offline, GeminiClient.mostTelling(listOf(busy, offline, quota)))
        assertEquals(busy, GeminiClient.mostTelling(listOf(retired, quota, busy)))
        assertEquals(quota, GeminiClient.mostTelling(listOf(retired, quota)))
    }
}
