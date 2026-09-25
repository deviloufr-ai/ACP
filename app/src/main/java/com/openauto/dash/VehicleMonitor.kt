package com.openauto.dash

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the car link working for the whole process rather than for one
 * dashboard screen: the OBD poll with the AI mechanic and car-care watchers,
 * and the adapter reconnect. Tied to the screen, they all stopped whenever it
 * was rebuilt (a language change, or Android reclaiming the launcher behind a
 * full-screen app) until the next resume.
 */
internal object VehicleMonitor {
    private const val POLL_MS = 500L
    private const val FIRST_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var appContext: Context? = null

    /** The launcher is in front: only then does a missing adapter get redialled. */
    private val foreground = MutableStateFlow(false)

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        scope.launch { pollWhileConnected() }
        scope.launch { reconnectWhileInFront() }
    }

    /** The second screen shows the car's readings: they are wanted even with an app in front. */
    private val secondScreen = MutableStateFlow(false)

    fun setForeground(inFront: Boolean) {
        foreground.value = inFront
    }

    fun setSecondScreenShowing(showing: Boolean) {
        secondScreen.value = showing
    }

    /**
     * Dials the saved adapter when the link is down and the Bluetooth
     * permission is held; nothing happens without a saved adapter.
     */
    fun connectSaved() {
        val context = appContext ?: return
        if (!ObdBluetoothManager.connectionState.value.isIdle) return
        val saved = ObdBluetoothManager.savedDeviceAddress() ?: return
        val missingPerms = requiredBluetoothPermissions().any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missingPerms) scope.launch { ObdBluetoothManager.connect(saved) }
    }

    private suspend fun pollWhileConnected() {
        combine(ObdBluetoothManager.connectionState, DemoMode.active) { connection, demo ->
            // The demo feeds the readings itself and must not scan, speak or save anything.
            connection == ObdConnectionState.CONNECTED && !demo
        }
            .distinctUntilChanged()
            .collectLatest { live ->
                if (!live) return@collectLatest
                coroutineScope {
                    // The AI mechanic checks for fault codes by itself once the first
                    // readings are in (it only speaks about codes it hasn't heard before).
                    launch {
                        delay(3000)
                        AiMechanic.autoScan()
                    }
                    while (true) {
                        ObdBluetoothManager.poll()
                        AiMechanic.watch(ObdBluetoothManager.data.value)
                        CarCare.watch(ObdBluetoothManager.data.value)
                        delay(POLL_MS)
                    }
                }
            }
    }

    /**
     * While the launcher is in front (or the second screen shows the car's
     * readings), redials a missing adapter: after 5 s,
     * then less and less often up to once a minute, so an adapter that is
     * unplugged or asleep does not keep the Bluetooth radio paging. Coming
     * back to the front, or a link that was up and dropped, starts over at 5 s.
     */
    private suspend fun reconnectWhileInFront() {
        combine(foreground, secondScreen) { inFront, cluster -> inFront || cluster }.distinctUntilChanged().collectLatest { inFront ->
            if (!inFront) return@collectLatest
            var wait = FIRST_RETRY_MS
            while (true) {
                if (ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED) {
                    ObdBluetoothManager.connectionState.first { it != ObdConnectionState.CONNECTED }
                    wait = FIRST_RETRY_MS
                }
                connectSaved()
                delay(wait)
                wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }
}
