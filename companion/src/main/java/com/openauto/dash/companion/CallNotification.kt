package com.openauto.dash.companion

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.BundleCompat
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A call in an app, as its notification tells it. WhatsApp, Signal, Telegram,
 * Messenger, Teams… carry their own calls (VoIP), which never reach the
 * phone's call state; but each announces the call with a notification of
 * category "call": Answer / Decline while it rings, Hang up once taken.
 * Android 12's CallStyle names those (the answer, decline and hang-up
 * intents are in the extras); for a notification without it, the actions are
 * told apart by their titles, else by their place, decline on the left and
 * answer on the right as Android lays them out.
 */
internal data class CallNotification(
    val key: String,
    val packageName: String,
    /** The app's name, as the head unit says it: "WhatsApp". */
    val appName: String,
    val postedAt: Long,
    /** Ringing (true) or taken. */
    val incoming: Boolean,
    /** Who is calling, as the app names them. */
    val caller: String?,
    /** Their photo from the notification, as a small PNG, base64. */
    val photoPng: String?,
    /** When the call was taken, wall clock; only when the notification says. */
    val startedAt: Long?,
    val answer: PendingIntent?,
    val decline: PendingIntent?,
    val hangUp: PendingIntent?
) {
    /** False: the head unit shows the call but its buttons would do nothing. */
    val canControl: Boolean get() = if (incoming) answer != null || decline != null else hangUp != null

    companion object {
        private const val TAG = "CallNotification"
        private const val PHOTO_PX = 96

        // Notification.EXTRA_CALL_TYPE & co (API 31), by key: read the same on
        // every Android this app runs on, and a CallStyle notification from a
        // newer app is understood the same way.
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_CALL_PERSON = "android.callPerson"
        private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_SCREENING = 3

        /** True for a notification announcing a call, ringing or taken. */
        fun isCall(n: Notification): Boolean =
            n.category == Notification.CATEGORY_CALL || n.extras.getInt(EXTRA_CALL_TYPE, 0) != 0

        /** The call [sbn] announces; null for any other notification. */
        fun parse(context: Context, sbn: StatusBarNotification): CallNotification? {
            val n = sbn.notification ?: return null
            if (!isCall(n)) return null
            val extras = n.extras
            val actions = n.actions.orEmpty().toList()

            val incoming: Boolean
            var answer: PendingIntent? = null
            var decline: PendingIntent? = null
            var hangUp: PendingIntent? = null
            when (extras.getInt(EXTRA_CALL_TYPE, 0)) {
                CALL_TYPE_INCOMING -> {
                    incoming = true
                    answer = pendingIntent(extras, EXTRA_ANSWER_INTENT)
                    decline = pendingIntent(extras, EXTRA_DECLINE_INTENT)
                }
                // A screened call: answer or hang up before it rings.
                CALL_TYPE_SCREENING -> {
                    incoming = true
                    answer = pendingIntent(extras, EXTRA_ANSWER_INTENT)
                    decline = pendingIntent(extras, EXTRA_HANG_UP_INTENT)
                }
                CALL_TYPE_ONGOING -> {
                    incoming = false
                    hangUp = pendingIntent(extras, EXTRA_HANG_UP_INTENT)
                }
                else -> {
                    val roles = CallActionRoles.of(actions.map { it.title?.toString().orEmpty() })
                    answer = roles.answer?.let { actions[it].actionIntent }
                    decline = roles.decline?.let { actions[it].actionIntent }
                    hangUp = roles.hangUp?.let { actions[it].actionIntent }
                    // Nothing named: a ringing call is the one that wants the whole screen.
                    incoming = if (roles.isEmpty) n.fullScreenIntent != null else roles.incoming
                }
            }

            // Who: CallStyle's person, else the title, else the people the notification is about.
            val person = runCatching { BundleCompat.getParcelable(extras, EXTRA_CALL_PERSON, Person::class.java) }.getOrNull()
                ?: runCatching { BundleCompat.getParcelableArrayList(extras, Notification.EXTRA_PEOPLE_LIST, Person::class.java) }.getOrNull()?.firstOrNull()
            val caller = listOfNotNull(
                person?.name, extras.getCharSequence(Notification.EXTRA_TITLE), extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ).map { it.toString().trim() }.firstOrNull { it.isNotEmpty() }
            val photo = (person?.icon ?: n.getLargeIcon())?.let { photoPng(context, it) }
            val startedAt = n.`when`.takeIf { !incoming && it > 0 && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) }
            return CallNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appLabel(context, sbn.packageName),
                postedAt = sbn.postTime,
                incoming = incoming,
                caller = caller,
                photoPng = photo,
                startedAt = startedAt,
                answer = answer,
                decline = decline,
                hangUp = hangUp
            )
        }

        private fun pendingIntent(extras: Bundle, key: String): PendingIntent? =
            runCatching { BundleCompat.getParcelable(extras, key, PendingIntent::class.java) }.getOrNull()

        private fun appLabel(context: Context, pkg: String): String {
            val pm = context.packageManager
            return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }

        private fun photoPng(context: Context, icon: Icon): String? = runCatching {
            val bitmap = icon.loadDrawable(context)?.toBitmap(PHOTO_PX, PHOTO_PX) ?: return null
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }.onFailure { Log.w(TAG, "caller photo unreadable", it) }.getOrNull()
    }
}
