package com.openauto.dash.companion

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.openauto.dash.link.ActionResult
import com.openauto.dash.link.CallCommand
import com.openauto.dash.link.Dismiss
import com.openauto.dash.link.Hello
import com.openauto.dash.link.LINK_PORT
import com.openauto.dash.link.LinkMessage
import com.openauto.dash.link.LinkSession
import com.openauto.dash.link.MarkRead
import com.openauto.dash.link.NotificationSync
import com.openauto.dash.link.Ping
import com.openauto.dash.link.Pong
import com.openauto.dash.link.Reply
import com.openauto.dash.link.SecureChannel
import com.openauto.dash.link.UnknownPairingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

sealed interface LinkState {
    data object Off : LinkState
    /** Listening: waiting for the head unit to join the hotspot and dial in. */
    data object Waiting : LinkState
    data class Connected(val unitName: String) : LinkState
}

/**
 * The phone's end of the link: a TCP server on [LINK_PORT]. The head unit, on
 * this phone's hotspot, dials the hotspot gateway (this phone), proves it
 * holds a pairing secret, then receives the notifications and sends back
 * replies. One head unit at a time; a new connection replaces the old one.
 * Run by [LinkService], which keeps the process alive.
 */
object LinkServer {
    private const val TAG = "LinkServer"
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    /** The head unit pings every 15 s; three missed pings and the link is dropped. */
    private const val IDLE_TIMEOUT_MS = 45_000

    private val _state = MutableStateFlow<LinkState>(LinkState.Off)
    val state: StateFlow<LinkState> = _state

    private val main = Handler(Looper.getMainLooper())
    // Everything written to the socket goes through one thread, in order, off the main thread.
    private val sender = Executors.newSingleThreadExecutor()
    private var server: ServerSocket? = null
    @Volatile private var session: LinkSession? = null
    @Volatile private var appContext: Context? = null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        appContext = app
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LINK_PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "cannot listen on $LINK_PORT", e)
            return
        }
        server = socket
        _state.value = LinkState.Waiting
        Thread({ acceptLoop(app, socket) }, "link-accept").start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        session?.close()
        session = null
        _state.value = LinkState.Off
    }

    /** Sends to the connected head unit, if any. Safe from any thread. */
    fun send(message: LinkMessage) {
        val current = session ?: return
        sender.execute {
            try {
                current.send(message)
            } catch (e: IOException) {
                current.close()
            }
        }
    }

    private fun acceptLoop(context: Context, socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
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
        } catch (e: IOException) {
            Log.i(TAG, "handshake failed: ${e.message}")
            runCatching { client.close() }
            return
        }
        client.soTimeout = IDLE_TIMEOUT_MS
        session?.close()
        session = link
        val unitName = PairedUnits.nameOf(context, link.pairingId) ?: "?"
        _state.value = LinkState.Connected(unitName)

        send(Hello(deviceName(context), appVersion(context)))
        send(NotificationSync(PhoneNotificationListener.snapshot()))
        send(PhoneCalls.snapshot())
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
                    if (server != null) _state.value = LinkState.Waiting
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
            is CallCommand -> main.post {
                // Refused (no permission, no call): tell the head unit what the call really is.
                if (!PhoneCalls.command(appContext ?: return@post, message.action)) send(PhoneCalls.snapshot())
            }
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
