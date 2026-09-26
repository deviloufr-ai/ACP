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

/**
 * Head unit → phone: where the car is, so the companion app can lead back to
 * it. Sent when the car comes to a stop (the last stop before it is switched
 * off is where it was parked), when the spot is saved on the dashboard's
 * Parking tile ([saved]), and when the link comes up. [at] is when the car was
 * there; the phone keeps the newest.
 */
@Serializable
@SerialName("car_location")
data class CarLocation(val lat: Double, val lng: Double, val at: Long, val saved: Boolean = false) : LinkMessage

/** Phone → head unit: every notification currently shown, sent after [Hello]. */
@Serializable
@SerialName("notif_sync")
data class NotificationSync(val notifications: List<PhoneNotification>) : LinkMessage {
    companion object {
        /** The head unit's card keeps 20 notifications in all: more would only be dropped there. */
        const val MAX_ITEMS = 20

        /**
         * A sync of [notifications] (newest first) that always fits in one frame:
         * at most [MAX_ITEMS], each app's icon only on its first notification
         * (the head unit keeps one icon per app, taken from the first it reads),
         * and the oldest left out until it fits in [maxBytes].
         */
        fun of(notifications: List<PhoneNotification>, maxBytes: Int = LinkSession.MAX_MESSAGE): NotificationSync {
            val withIcon = HashSet<String>()
            var items = notifications.take(MAX_ITEMS).map { n ->
                if (n.iconPng == null || withIcon.add(n.packageName)) n else n.copy(iconPng = null)
            }
            // Dropping from the end never drops an app's icon while one of its notifications stays.
            while (items.isNotEmpty() && LinkCodec.encode(NotificationSync(items)).size > maxBytes) items = items.dropLast(1)
            return NotificationSync(items)
        }
    }
}

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

/**
 * Phone → head unit: the phone call, each time it changes (and once when the
 * link comes up). A phone call, or a call in an app (WhatsApp, Signal,
 * Telegram…, [app]) seen through its call notification.
 */
@Serializable
@SerialName("call")
data class CallState(
    val phase: Phase,
    /** The other party's number, when the phone can read it. */
    val number: String? = null,
    /** Their name from the phone's contacts (or as the calling app names them). */
    val name: String? = null,
    /** Their contact photo as a small PNG, base64. */
    val photoPng: String? = null,
    /** How long the call has been answered, when sent (clocks may differ between the two). */
    val activeForMs: Long = 0,
    /** False when the companion isn't allowed to answer / hang up: the head unit only shows the call. */
    val canControl: Boolean = true,
    /** The app the call is in ("WhatsApp"…); null for a phone call. */
    val app: String? = null
) : LinkMessage {
    @Serializable
    enum class Phase { IDLE, RINGING, ACTIVE }
}

/** Head unit → phone: answer, decline or end the call. */
@Serializable
@SerialName("call_cmd")
data class CallCommand(val action: Action) : LinkMessage {
    @Serializable
    enum class Action { ANSWER, DECLINE, HANG_UP }
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
