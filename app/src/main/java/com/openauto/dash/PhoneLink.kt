package com.openauto.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.openauto.dash.link.ActionResult
import com.openauto.dash.link.Dismiss
import com.openauto.dash.link.Hello
import com.openauto.dash.link.LINK_PORT
import com.openauto.dash.link.LinkMessage
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.MarkRead
import com.openauto.dash.link.NotificationPosted
import com.openauto.dash.link.NotificationRemoved
import com.openauto.dash.link.NotificationSync
import com.openauto.dash.link.PairingOffer
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.Reply
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.UnknownPairingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/*
 * The phone link, head unit side. The driver's phone shares its connection
 * over Wi-Fi; the Dashwheel Companion app on it listens on the hotspot's
 * gateway address. This side finds that gateway, dials it, proves it holds
 * the secret from the pairing QR code, then shows the phone's notifications
 * in the Notifications card and sends back replies.
 */

/** A phone the driver paired by scanning the QR code with the companion app. */
data class PairedPhone(
    val id: String,
    val secret: ByteArray,
    /** From the phone's [Hello]; empty until it first connected. */
    val name: String,
    val pairedAt: Long,
    /** The phone said it no longer knows this pairing: it must be paired again. */
    val forgotten: Boolean = false
)

sealed interface PhoneLinkState {
    /** No phone paired. */
    data object Unpaired : PhoneLinkState
    /** Paired, waiting for the phone's hotspot and companion app. */
    data object Searching : PhoneLinkState
    data class Connected(val phoneName: String) : PhoneLinkState
}

object PhoneLink {
    private const val TAG = "PhoneLink"
    private const val PREFS = "phone_link"
    private const val KEY_PHONES = "phones"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val PING_EVERY_MS = 15_000L
    /** How often to look for the phone: quickly while a pairing code is on screen. */
    private const val RETRY_MS = 10_000L
    private const val RETRY_PAIRING_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val _state = MutableStateFlow<PhoneLinkState>(PhoneLinkState.Unpaired)
    val state: StateFlow<PhoneLinkState> = _state

    private val _phones = MutableStateFlow<List<PairedPhone>>(emptyList())
    val phones: StateFlow<List<PairedPhone>> = _phones

    /** The pairing whose QR code is on screen, until a phone uses it. */
    private val _pending = MutableStateFlow<PairingOffer?>(null)
    val pending: StateFlow<PairingOffer?> = _pending

    /** How the phone carried out a reply / mark-as-read / dismiss. */
    private val _results = MutableSharedFlow<ActionResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ActionResult> = _results

    /** Bumped when the network changes or the phones change, to retry right away. */
    private val wake = MutableStateFlow(0)
    @Volatile private var session: LinkSession? = null

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        _phones.value = readPhones(app)
        refreshIdleState()
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wake.update { it + 1 }
                override fun onLost(network: Network) = wake.update { it + 1 }
            }
        )
        scope.launch { run(app) }
    }

    /** A fresh pairing code to show; replaces any previous one. */
    fun beginPairing(context: Context): PairingOffer {
        val offer = PairingOffer.create(unitName(context))
        _pending.value = offer
        wake.update { it + 1 }
        return offer
    }

    fun cancelPairing() {
        _pending.value = null
    }

    fun forget(context: Context, id: String) {
        savePhones(context, _phones.value.filter { it.id != id })
        session?.takeIf { it.pairingId == id }?.close()
        refreshIdleState()
    }

    /** Sends to the connected phone; false when no phone is connected. */
    fun send(message: LinkMessage): Boolean {
        val current = session ?: return false
        scope.launch {
            try {
                current.send(message)
            } catch (e: IOException) {
                current.close()
            }
        }
        return true
    }

    fun reply(key: String, text: String) = send(Reply(NotificationFeed.phoneKey(key), text))
    fun markRead(key: String) = send(MarkRead(NotificationFeed.phoneKey(key)))
    fun dismiss(key: String) = send(Dismiss(NotificationFeed.phoneKey(key)))

    private suspend fun run(context: Context) {
        var last = wake.value
        while (scope.isActive) {
            val pending = _pending.value
            val candidates = listOfNotNull(pending?.let { PairedPhone(it.id, it.secret, "", 0) }) +
                _phones.value.filter { !it.forgotten }
            val gateway = if (candidates.isEmpty()) null else hotspotGateway(context)
            if (gateway != null) {
                for (phone in candidates) {
                    if (tryPhone(context, gateway, phone, isPending = phone.id == pending?.id)) break
                }
            }
            refreshIdleState()
            val wait = if (_pending.value != null) RETRY_PAIRING_MS else RETRY_MS
            withTimeoutOrNull(wait) { wake.first { it != last } }
            last = wake.value
        }
    }

    /** One attempt with one pairing; true when the phone answered (and the link has now ended). */
    private fun tryPhone(context: Context, gateway: InetAddress, phone: PairedPhone, isPending: Boolean): Boolean {
        val socket = Socket()
        val link = try {
            socket.connect(InetSocketAddress(gateway, LINK_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            SecureChannel.client(
                socket.getInputStream(), socket.getOutputStream(), phone.id, phone.secret,
                onClose = { runCatching { socket.close() } }
            )
        } catch (e: UnknownPairingException) {
            runCatching { socket.close() }
            // The phone removed this car: show it as needing a new pairing.
            if (!isPending) savePhones(context, _phones.value.map { if (it.id == phone.id) it.copy(forgotten = true) else it })
            return true
        } catch (e: IOException) {
            runCatching { socket.close() }
            return false
        }

        session = link
        val pinger = scope.launch {
            while (isActive) {
                delay(PING_EVERY_MS)
                send(Ping)
            }
        }
        try {
            link.send(Hello(unitName(context), BuildConfig.VERSION_NAME))
            while (true) {
                val message = link.receive() ?: continue
                handle(context, phone, isPending, message)
            }
        } catch (e: IOException) {
            Log.i(TAG, "link ended: ${e.message}")
        } finally {
            pinger.cancel()
            link.close()
            if (session === link) session = null
            NotificationFeed.phoneClear()
        }
        return true
    }

    private fun handle(context: Context, phone: PairedPhone, isPending: Boolean, message: LinkMessage) {
        when (message) {
            is Hello -> {
                if (isPending) {
                    // The QR code was used: this phone is now paired.
                    _pending.value = null
                    val paired = PairedPhone(phone.id, phone.secret, message.deviceName, System.currentTimeMillis())
                    savePhones(context, _phones.value.filter { it.id != phone.id } + paired)
                } else if (message.deviceName != phone.name) {
                    savePhones(context, _phones.value.map { if (it.id == phone.id) it.copy(name = message.deviceName) else it })
                }
                _state.value = PhoneLinkState.Connected(message.deviceName)
            }
            Ping -> send(Pong)
            is NotificationSync -> NotificationFeed.phoneSync(message.notifications)
            is NotificationPosted -> NotificationFeed.phonePosted(message.notification)
            is NotificationRemoved -> NotificationFeed.phoneRemoved(message.key)
            is ActionResult -> _results.tryEmit(message)
            else -> Unit
        }
    }

    private fun refreshIdleState() {
        if (session != null && _state.value is PhoneLinkState.Connected) return
        _state.value = if (_phones.value.isEmpty() && _pending.value == null) PhoneLinkState.Unpaired else PhoneLinkState.Searching
    }

    /**
     * The phone's address when this head unit is on its hotspot: the Wi-Fi
     * network's default gateway. Android 11+ picks a random hotspot subnet,
     * so it is read from the network rather than assumed to be 192.168.43.1.
     */
    private fun hotspotGateway(context: Context): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") // allNetworks: the Wi-Fi network may not be the default one.
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        return networks.distinct().firstNotNullOfOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
            cm.getLinkProperties(network)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
        }
    }

    private fun unitName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "Dashwheel"

    private fun readPhones(context: Context): List<PairedPhone> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PHONES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                PairedPhone(
                    o.getString("id"), Base64.getDecoder().decode(o.getString("secret")),
                    o.optString("name"), o.optLong("pairedAt"), o.optBoolean("forgotten")
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun savePhones(context: Context, phones: List<PairedPhone>) {
        val array = JSONArray()
        phones.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("secret", Base64.getEncoder().encodeToString(it.secret))
                    .put("name", it.name)
                    .put("pairedAt", it.pairedAt)
                    .put("forgotten", it.forgotten)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PHONES, array.toString()).apply()
        _phones.value = phones
        wake.update { it + 1 }
    }
}
