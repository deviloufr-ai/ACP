package com.openauto.dash.link

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * What the head unit's pairing QR code carries: a random id for this pairing,
 * the head unit's name to show on the phone, and the 32-byte secret both sides
 * then prove they hold on every connection. Anyone who sees the code can pair,
 * so it is only shown on demand and replaced each time.
 *
 * `dashwheel://pair?v=1&id=<hex>&n=<name>&k=<base64url secret>`
 */
class PairingOffer(val id: String, val unitName: String, val secret: ByteArray) {

    fun toUri(): String =
        "$SCHEME://$HOST?v=$VERSION&id=$id&n=${URLEncoder.encode(unitName, "UTF-8")}" +
            "&k=${Base64.getUrlEncoder().withoutPadding().encodeToString(secret)}"

    companion object {
        const val SCHEME = "dashwheel"
        const val HOST = "pair"
        private const val VERSION = 1
        const val SECRET_BYTES = 32
        private val ID = Regex("[0-9a-f]{16}")

        fun create(unitName: String, random: SecureRandom = SecureRandom()): PairingOffer {
            val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
            return PairingOffer(id, unitName.take(64), secret)
        }

        /** Null for anything that isn't a well-formed offer of a version this side knows. */
        fun parse(text: String): PairingOffer? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null
                else part.substring(0, eq) to runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull()
            }.toMap()
            if (params["v"] != VERSION.toString()) return null
            val id = params["id"]?.takeIf { ID.matches(it) } ?: return null
            val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: return null
            val secret = params["k"]
                ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
                ?.takeIf { it.size == SECRET_BYTES } ?: return null
            return PairingOffer(id, name, secret)
        }
    }
}
