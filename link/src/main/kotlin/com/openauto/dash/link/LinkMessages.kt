package com.openauto.dash.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/*
 * What the phone and the head unit say to each other once the channel is up.
 * One JSON object per encrypted frame, told apart by its "t" field. Both
 * sides skip a message they don't know, so a newer companion app still talks
 * to an older launcher and the other way round.
 */

/** Bumped when a change can't be skipped over by the older side. */
const val PROTOCOL_VERSION = 1

/** TCP port the companion app listens on, on the phone's hotspot address. */
const val LINK_PORT = 47810

@Serializable
sealed interface LinkMessage

/** First message each way once the channel is up: who is on the other end. */
@Serializable
@SerialName("hello")
data class Hello(
    val deviceName: String,
    val appVersion: String,
    val protocol: Int = PROTOCOL_VERSION
) : LinkMessage

/** Keep-alive; answered with [Pong]. A silent link is dropped and redialled. */
@Serializable
@SerialName("ping")
data object Ping : LinkMessage

@Serializable
@SerialName("pong")
data object Pong : LinkMessage

/** Phone → head unit: every notification currently shown, sent after [Hello]. */
@Serializable
@SerialName("notif_sync")
data class NotificationSync(val notifications: List<PhoneNotification>) : LinkMessage

/** Phone → head unit: a notification appeared or changed. */
@Serializable
@SerialName("notif")
data class NotificationPosted(val notification: PhoneNotification) : LinkMessage

/** Phone → head unit: a notification went away on the phone. */
@Serializable
@SerialName("notif_removed")
data class NotificationRemoved(val key: String) : LinkMessage

/** Head unit → phone: answer a conversation through the app's own reply action. */
@Serializable
@SerialName("reply")
data class Reply(val key: String, val text: String) : LinkMessage

/** Head unit → phone: mark a conversation as read, as the app's own action does. */
@Serializable
@SerialName("mark_read")
data class MarkRead(val key: String) : LinkMessage

/** Head unit → phone: clear a notification from the phone's shade. */
@Serializable
@SerialName("dismiss")
data class Dismiss(val key: String) : LinkMessage

/** Phone → head unit: how a [Reply], [MarkRead] or [Dismiss] went. */
@Serializable
@SerialName("result")
data class ActionResult(val key: String, val action: Action, val ok: Boolean) : LinkMessage {
    @Serializable
    enum class Action { REPLY, MARK_READ, DISMISS }
}

/** A notification as the head unit shows it. [key] is the phone's own key. */
@Serializable
data class PhoneNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** The latest lines of a messaging conversation, oldest first; empty otherwise. */
    val messages: List<ConversationLine> = emptyList(),
    val canReply: Boolean = false,
    val canMarkRead: Boolean = false,
    /** The app's icon as a small PNG, base64. */
    val iconPng: String? = null
)

@Serializable
data class ConversationLine(val sender: String, val text: String, val at: Long)

/** JSON <-> [LinkMessage]. */
object LinkCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
    }

    fun encode(message: LinkMessage): ByteArray =
        json.encodeToString(LinkMessage.serializer(), message).encodeToByteArray()

    /** Null for a message this side doesn't know (a newer peer) or can't read. */
    fun decode(bytes: ByteArray): LinkMessage? =
        try {
            json.decodeFromString(LinkMessage.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
