package com.openauto.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that draws the launcher's menu + info as a floating widget
 * on top of the split-screen panes (Maps | media), using a `TYPE_APPLICATION_OVERLAY`
 * window. Requires the "Display over other apps" permission.
 *
 * The overlay is a left-edge rail of circular buttons (favorite apps launch
 * fullscreen, an app-launcher button reopens the home drawer, plus the
 * Assistant) with a clock / speed / now-playing info block underneath.
 */
class LauncherOverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var mediaController: CarMediaController? = null

    private var clockView: TextView? = null
    private var speedView: TextView? = null
    private var trackView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            // Permission revoked; nothing to show.
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayView == null) addOverlay()
        return START_STICKY
    }

    private fun addOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val rail = buildRail()
        overlayView = rail

        val params = WindowManager.LayoutParams(
            dp(96),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.TOP }

        wm.addView(rail, params)
        observeInfo()
    }

    /** Builds the vertical rail: apps + favorites + assistant + info. */
    private fun buildRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(14), dp(6), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6141518"))
                cornerRadius = dp(28).toFloat()
            }
        }

        // App launcher (reopens the home drawer).
        rail.addView(
            circleButton(glyph = "⬚", bg = ACCENT, textColor = BACKGROUND) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
        rail.addView(spacer(dp(14)))

        // Up to five favorites, launched fullscreen over the split.
        val favorites = AppLauncher.pickFavorites(AppLauncher.loadApps(this), 5)
        favorites.forEach { app ->
            rail.addView(appButton(app))
            rail.addView(spacer(dp(12)))
        }

        // Assistant.
        rail.addView(
            circleButton(glyph = "🎙", bg = Color.parseColor("#4285F4"), textColor = Color.WHITE) {
                launchAssistant()
            }
        )

        rail.addView(spacer(0, weight = 1f))

        // Info block: clock / speed / now-playing.
        clockView = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 18f
            text = currentClock()
        }
        speedView = TextView(this).apply {
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 12f
            text = "-- km/h"
        }
        trackView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            maxLines = 1
            text = ""
        }
        rail.addView(clockView)
        rail.addView(speedView)
        rail.addView(trackView)

        return rail
    }

    private fun appButton(app: AppEntry): View {
        val size = dp(56)
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageDrawable(app.icon)
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CARD_HI)
            }
            setOnClickListener { AppLauncher.launch(this@LauncherOverlayService, app.packageName) }
        }
    }

    private fun circleButton(glyph: String, bg: Int, textColor: Int, onClick: () -> Unit): View {
        val size = dp(56)
        val label = TextView(this).apply {
            text = glyph
            setTextColor(textColor)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            }
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            setOnClickListener { onClick() }
        }
    }

    private fun spacer(height: Int, weight: Float = 0f): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height,
                weight
            )
        }

    private fun observeInfo() {
        scope.launch {
            while (true) {
                clockView?.text = currentClock()
                delay(1000)
            }
        }
        ObdBluetoothManager.data
            .onEach { data ->
                val connected = ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED
                speedView?.text = if (connected) "${data.speedKmh} km/h" else "-- km/h"
            }
            .launchIn(scope)

        val controller = CarMediaController(this).also { mediaController = it }
        controller.start()
        controller.mediaState
            .onEach { state ->
                trackView?.text = if (state.hasMedia) state.title else ""
            }
            .launchIn(scope)
    }

    private fun launchAssistant() {
        for (action in listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
    }

    private fun startAsForeground() {
        val channelId = "cockpit_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Cockpit overlay",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("OpenAuto Dash cockpit")
            .setContentText("Menu overlay is active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        mediaController?.stop()
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun currentClock(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    companion object {
        private const val NOTIFICATION_ID = 42
        private val BACKGROUND = Color.parseColor("#0B0C0F")
        private val ACCENT = Color.parseColor("#8AB4F8")
        private val CARD_HI = Color.parseColor("#2A2D33")

        /** Starts the overlay if the "display over other apps" permission is held. */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, LauncherOverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LauncherOverlayService::class.java))
        }
    }
}
