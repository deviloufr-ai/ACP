package com.openauto.dash.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.link.CallCommand
import com.openauto.dash.link.CallState
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The phone call, for the car: follows the phone's call state (the system's
 * PHONE_STATE broadcast), finds the caller in the contacts, and answers or
 * ends the call when the head unit asks, through [TelecomManager] as a
 * smartwatch app does. The call's sound stays on the car's Bluetooth
 * hands-free; this only carries who is calling and the buttons.
 */
object PhoneCalls {
    private const val TAG = "PhoneCalls"
    private const val PHOTO_PX = 96

    /** What the head unit is told now; re-sent whole when the link comes up. */
    @Volatile
    var current: CallState = CallState(CallState.Phase.IDLE)
        private set

    private var answeredAt = 0L
    private var receiver: BroadcastReceiver? = null

    /** Listens while [LinkService] runs. */
    fun start(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onPhoneState(app, intent)
        }
        // A protected system broadcast: only the system can send it.
        ContextCompat.registerReceiver(app, r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    /** The state to send now, its answered time brought up to date. */
    fun snapshot(): CallState {
        val c = current
        return if (c.phase == CallState.Phase.ACTIVE) c.copy(activeForMs = SystemClock.elapsedRealtime() - answeredAt) else c
    }

    /** Carries out the head unit's [action]. False when not allowed or nothing to act on. */
    @SuppressLint("MissingPermission") // checked by canControl()
    fun command(context: Context, action: CallCommand.Action): Boolean {
        if (!canControl(context)) return false
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            when (action) {
                CallCommand.Action.ANSWER -> {
                    telecom.acceptRingingCall()
                    true
                }
                // Deprecated for new designs (an InCallService), but still what a
                // non-dialer app with ANSWER_PHONE_CALLS uses to end a call.
                CallCommand.Action.DECLINE, CallCommand.Action.HANG_UP -> @Suppress("DEPRECATION") telecom.endCall()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "call $action refused", e)
            false
        }
    }

    fun canControl(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    private fun onPhoneState(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        // Only there with READ_CALL_LOG; the broadcast then comes twice, with and without it.
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
        val before = current
        val phase = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallState.Phase.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallState.Phase.ACTIVE
            else -> CallState.Phase.IDLE
        }
        if (phase == CallState.Phase.IDLE) {
            current = CallState(CallState.Phase.IDLE)
        } else {
            if (phase == CallState.Phase.ACTIVE && before.phase != CallState.Phase.ACTIVE) answeredAt = SystemClock.elapsedRealtime()
            // The same call keeps its caller when a later broadcast lacks the number.
            val sameCall = before.phase != CallState.Phase.IDLE
            val known = number ?: before.number.takeIf { sameCall }
            val contact = if (known != null && known != before.number) lookUp(context, known) else null
            current = CallState(
                phase = phase,
                number = known,
                name = contact?.first ?: before.name.takeIf { sameCall && known == before.number },
                photoPng = contact?.second ?: before.photoPng.takeIf { sameCall && known == before.number },
                canControl = canControl(context)
            )
        }
        if (current != before) LinkServer.send(snapshot())
    }

    /** (name, photo) from the phone's contacts, when allowed and found. */
    private fun lookUp(context: Context, number: String): Pair<String?, String?>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0)
                val photo = c.getString(1)?.let { photoPng(context, Uri.parse(it)) }
                name to photo
            }
        }.onFailure { Log.w(TAG, "contact lookup failed", it) }.getOrNull()
    }

    private fun photoPng(context: Context, uri: Uri): String? = runCatching {
        val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, PHOTO_PX, PHOTO_PX, true)
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        Base64.getEncoder().encodeToString(bytes.toByteArray())
    }.getOrNull()
}
