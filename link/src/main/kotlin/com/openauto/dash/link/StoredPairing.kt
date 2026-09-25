package com.openauto.dash.link

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.Base64

/**
 * A pairing as both apps keep it in their private preferences: a JSON array
 * of these, the secret in base64. The launcher and the companion share this
 * format; each side only sets the fields it uses.
 */
@Serializable
data class StoredPairing(
    val id: String,
    @Serializable(with = Base64Bytes::class)
    val secret: ByteArray,
    /** The other side's name: the car's on the phone, the phone's on the head unit (empty until known). */
    val name: String = "",
    val pairedAt: Long = 0,
    /** Head unit: the phone no longer knows this pairing, so it must be paired again. */
    val forgotten: Boolean = false,
    /** Head unit: occasions on which the phone's hotspot refused this pairing, since it last connected. */
    val refusals: Int = 0,
    val lastRefusalAt: Long = 0
)

object PairingStorage {
    // Defaults left out: an older version of either app still reads what this one wrote.
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(StoredPairing.serializer())

    fun encode(pairings: List<StoredPairing>): String = json.encodeToString(list, pairings)

    /** The pairings in [raw]; a damaged entry is skipped rather than losing every pairing. */
    fun decode(raw: String?): List<StoredPairing> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { runCatching { json.decodeFromJsonElement(StoredPairing.serializer(), it) }.getOrNull() }
    }
}

/** A secret as standard base64, as both apps have always stored it. */
internal object Base64Bytes : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("com.openauto.dash.link.Base64Bytes", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}
