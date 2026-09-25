package com.openauto.dash.companion

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.openauto.dash.link.ActionResult
import com.openauto.dash.link.Dismiss
import com.openauto.dash.link.Hello
import com.openauto.dash.link.LINK_PORT
import com.openauto.dash.link.LinkMessage
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.MarkRead
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.Reply
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.UnknownPairingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

sealed interface LinkState {
    data object Off : LinkState
    /** Listening: waiting for the head unit to join the hotspot and dial in. */
    data object Waiting : LinkState
    data class Connected(val unitName: String) : LinkState
}

/**
 * The phone's end of the link: a TCP server on [LINK_PORT], on the hotspot's
 * address only, so nothing on a café's Wi-Fi or the mobile network can reach
 * it. The head unit, on this phone's hotspot, dials the hotspot gateway (this
 * phone), proves it holds a pairing secret, then receives the notifications
 * and sends back replies. One head unit at a time; a new connection replaces
 * the old one. Run by [LinkService], which keeps the process alive.
 */
object LinkServer {
    private const val TAG = "LinkServer"
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    /** The head unit pings every 15 s; three missed pings and the link is dropped. */
    private const val IDLE_TIMEOUT_MS = 45_000
    /** How often to check the hotspot: switched on or off, or moved to another address. */
    private const val WATCH_MS = 5_000L
    /** Connections still proving their pairing at once; more are turned away. */
    private const val MAX_HANDSHAKES = 4
    // How phones name the hotspot's interface: wlan1, swlan0, ap0, softap0, ap_br_wlan2...
    private val HOTSPOT_NAME = Regex("(wlan|swlan|ap|softap|wifi|wigig).*")
    // Never the hotspot: mobile data, VPNs, tunnels, Wi-Fi Direct.
    private val NOT_HOTSPOT = Regex("(rmnet|ccmni|seth|epdg|tun|ppp|dummy|v4-|clat|ip6|sit|ifb|lo|p2p).*")

    private val _state = MutableStateFlow<LinkState>(LinkState.Off)
    val state: StateFlow<LinkState> = _state

    private val main = Handler(Looper.getMainLooper())
    // Everything written to the socket goes through one thread, in order, off the main thread.
    private val sender = Executors.newSingleThreadExecutor()
    private val handshakes = Semaphore(MAX_HANDSHAKES)
    private var running = false
    private var watcher: ScheduledExecutorService? = null
    private var server: ServerSocket? = null
    @Volatile private var session: LinkSession? = null

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        _state.value = LinkState.Waiting
        watcher = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ follow(app) }, 0, WATCH_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        running = false
        watcher?.shutdownNow()
        watcher = null
        closeServer()
        session?.close()
        session = null
        _state.value = LinkState.Off
    }

    /** Sends to the connected head unit, if any. Safe from any thread. */
    fun send(message: LinkMessage) {
        val current = session ?: return
        sender.execute { current.sendOrClose(message) }
    }

    /** Listens on the hotspot's current address, and not at all while there is no hotspot. */
    private fun follow(context: Context) {
        try {
            val hotspot = hotspotAddress(context)
            synchronized(this) {
                if (!running) return
                val current = server
                if (current != null && !current.isClosed && current.inetAddress == hotspot?.address) return
                closeServer()
                if (hotspot == null) return
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(hotspot.address, LINK_PORT))
                }
                server = socket
                Thread({ acceptLoop(context, socket, hotspot) }, "link-accept").start()
            }
        } catch (e: Exception) {
            // Thrown out of here, the check would never run again.
            Log.w(TAG, "cannot listen on the hotspot", e)
        }
    }

    private fun closeServer() {
        runCatching { server?.close() }
        server = null
    }

    /**
     * This phone's hotspot address. Its own networks (Wi-Fi it joined, mobile
     * data, a VPN) are exactly the ones to stay off; the hotspot's interface
     * isn't one of them. Android 11+ picks a random hotspot subnet, so the
     * address is read, never assumed to be 192.168.43.1.
     */
    private fun hotspotAddress(context: Context): InterfaceAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") // allNetworks: every network the phone itself uses, not just the default one.
        val own = cm.allNetworks.mapNotNullTo(HashSet()) { cm.getLinkProperties(it)?.interfaceName }
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { nif ->
            nif.name !in own && !NOT_HOTSPOT.matches(nif.name) && runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)
        }
        return candidates.sortedByDescending { HOTSPOT_NAME.matches(it.name) }.firstNotNullOfOrNull { nif ->
            nif.interfaceAddresses.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
        }
    }

    /** Whether [remote] is on the hotspot's own subnet, as a head unit that joined it is. */
    private fun onHotspot(remote: InetAddress?, hotspot: InterfaceAddress): Boolean {
        val a = remote?.address ?: return false
        val b = hotspot.address.address
        if (a.size != b.size) return false
        val bits = hotspot.networkPrefixLength.toInt().takeIf { it in 8..30 } ?: 24
        for (i in a.indices) {
            val take = (bits - i * 8).coerceIn(0, 8)
            if (take == 0) break
            val mask = (0xFF shl (8 - take)) and 0xFF
            if (a[i].toInt() and mask != b[i].toInt() and mask) return false
        }
        return true
    }

    private fun acceptLoop(context: Context, socket: ServerSocket, hotspot: InterfaceAddress) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            // Turned away before a byte is read: anyone not on the hotspot, and floods.
            if (!onHotspot(client.inetAddress, hotspot) || !handshakes.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            Thread({ serve(context, client) }, "link-session").start()
        }
    }

    private fun serve(context: Context, client: Socket) {
        val link = try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.tcpNoDelay = true
            SecureChannel.server(
                client.getInputStream(), client.getOutputStream(),
                secretFor = { PairedUnits.secretFor(context, it) },
                onClose = { runCatching { client.close() } }
            )
        } catch (e: UnknownPairingException) {
            runCatching { client.close() }
            return
        } catch (e: Exception) {
            Log.i(TAG, "handshake failed: ${e.message}")
            runCatching { client.close() }
            return
        } finally {
            handshakes.release()
        }
        client.soTimeout = IDLE_TIMEOUT_MS
        val unitName = PairedUnits.nameOf(context, link.pairingId) ?: "?"
        // Published under the lock, so a stop() during the handshake is never missed.
        val replaced: LinkSession?
        synchronized(this) {
            if (!running) {
                link.close()
                return
            }
            replaced = session
            session = link
            _state.value = LinkState.Connected(unitName)
        }
        replaced?.close()

        send(Hello(deviceName(context), appVersion(context)))
        send(PhoneNotificationListener.syncMessage())
        try {
            while (true) {
                val message = link.receive() ?: continue
                handle(link, message)
            }
        } catch (e: IOException) {
            // Link gone: the head unit left the hotspot, stopped, or went quiet.
        } finally {
            link.close()
            synchronized(this) {
                if (session === link) {
                    session = null
                    if (running) _state.value = LinkState.Waiting
                }
            }
        }
    }

    private fun handle(link: LinkSession, message: LinkMessage) {
        // A removed pairing ends the link it is using.
        if (PairedUnits.units.value.none { it.id == link.pairingId }) {
            link.close()
            return
        }
        when (message) {
            Ping -> send(Pong)
            is Reply -> onMain(message.key, ActionResult.Action.REPLY) { it.reply(message.key, message.text) }
            is MarkRead -> onMain(message.key, ActionResult.Action.MARK_READ) { it.markRead(message.key) }
            is Dismiss -> onMain(message.key, ActionResult.Action.DISMISS) { it.dismiss(message.key) }
            else -> Unit
        }
    }

    /** Notification actions run on the main thread, where the listener lives. */
    private fun onMain(key: String, action: ActionResult.Action, run: (PhoneNotificationListener) -> Boolean) {
        main.post {
            val ok = PhoneNotificationListener.instance?.let { runCatching { run(it) }.getOrDefault(false) } ?: false
            send(ActionResult(key, action, ok))
        }
    }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
}
