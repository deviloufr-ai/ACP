package com.openauto.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * It starts as a single round button; tapping it expands to a left-edge rail
 * (favorite apps, Assistant, and a clock / speed / now-playing info block), and
 * the top button collapses it back to the single button.
 */
class LauncherOverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var mediaController: CarMediaController? = null

    private var clockView: TextView? = null
    private var speedView: TextView? = null
    private var trackView: TextView? = null

    private enum class OverlayState { COLLAPSED, RAIL, APP_LIST, SPLIT_PICKER_LEFT, SPLIT_PICKER_RIGHT }
    private var currentState = OverlayState.COLLAPSED
    private var chosenLeftPackage: String? = null

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

        val container = FrameLayout(this)
        overlayView = container
        wm.addView(container, collapsedParams())
        renderCollapsed(container)
        observeInfo()
    }

    private fun collapsedParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(60),
            dp(60),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(6)
        }

    private fun expandedParams(withAppList: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            if (withAppList) WindowManager.LayoutParams.MATCH_PARENT else dp(96),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.TOP }

    /** Switches the overlay between the single button, the rail, and the full app drawer. */
    private fun updateOverlayState(state: OverlayState) {
        currentState = state
        val container = overlayView as? FrameLayout ?: return
        container.removeAllViews()

        when (state) {
            OverlayState.COLLAPSED -> {
                renderCollapsed(container)
                windowManager?.updateViewLayout(container, collapsedParams())
            }
            OverlayState.RAIL -> {
                container.addView(
                    buildRail(showAppList = false),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                windowManager?.updateViewLayout(container, expandedParams(withAppList = false))
            }
            OverlayState.APP_LIST -> {
                val rootLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                rootLayout.addView(buildRail(showAppList = true))
                rootLayout.addView(buildAppGrid(pickerStage = 0))
                container.addView(rootLayout)
                windowManager?.updateViewLayout(container, expandedParams(withAppList = true))
            }
            OverlayState.SPLIT_PICKER_LEFT -> {
                val rootLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                rootLayout.addView(buildRail(showAppList = true))
                rootLayout.addView(buildAppGrid(pickerStage = 1))
                container.addView(rootLayout)
                windowManager?.updateViewLayout(container, expandedParams(withAppList = true))
            }
            OverlayState.SPLIT_PICKER_RIGHT -> {
                val rootLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                rootLayout.addView(buildRail(showAppList = true))
                rootLayout.addView(buildAppGrid(pickerStage = 2))
                container.addView(rootLayout)
                windowManager?.updateViewLayout(container, expandedParams(withAppList = true))
            }
        }
    }

    /** Collapsed state: one round button that opens the menu when tapped. */
    private fun renderCollapsed(container: FrameLayout) {
        val button = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
            addView(
                TextView(this@LauncherOverlayService).apply {
                    text = "≡" // ≡ menu
                    setTextColor(BACKGROUND)
                    textSize = 24f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            setOnClickListener { updateOverlayState(OverlayState.RAIL) }
        }
        container.addView(button, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
    }

    /** Builds the vertical rail: apps + favorites + assistant + info. */
    private fun buildRail(showAppList: Boolean): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(14), dp(6), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6141518"))
                cornerRadius = dp(28).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.MATCH_PARENT)
        }

        // Collapse back to the single button.
        rail.addView(
            circleButton(glyph = "×", bg = CARD_HI, textColor = Color.WHITE) {
                updateOverlayState(OverlayState.COLLAPSED)
            }
        )
        rail.addView(spacer(dp(10)))

        // 1. Home dashboard button
        rail.addView(
            circleButton(glyph = "🏠", bg = CARD_HI, textColor = Color.WHITE) {
                val intent = Intent(this@LauncherOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
                updateOverlayState(OverlayState.COLLAPSED)
            }
        )
        rail.addView(spacer(dp(10)))

        // 2. Dynamic Layout Quick-Toggle / App Selection Picker for Custom Split Screen
        val isPickerActive = currentState == OverlayState.SPLIT_PICKER_LEFT || currentState == OverlayState.SPLIT_PICKER_RIGHT
        rail.addView(
            circleButton(
                glyph = "⇄",
                bg = if (isPickerActive) ACCENT else Color.parseColor("#2DD4BF"),
                textColor = if (isPickerActive) BACKGROUND else Color.BLACK
            ) {
                if (isPickerActive) {
                    updateOverlayState(OverlayState.RAIL)
                } else {
                    updateOverlayState(OverlayState.SPLIT_PICKER_LEFT)
                }
            }
        )
        rail.addView(spacer(dp(10)))

        // 3. All Apps toggler button
        rail.addView(
            circleButton(
                glyph = "㗊",
                bg = if (currentState == OverlayState.APP_LIST) ACCENT else CARD_HI,
                textColor = if (currentState == OverlayState.APP_LIST) BACKGROUND else Color.WHITE
            ) {
                if (currentState == OverlayState.APP_LIST) {
                    updateOverlayState(OverlayState.RAIL)
                } else {
                    updateOverlayState(OverlayState.APP_LIST)
                }
            }
        )
        rail.addView(spacer(dp(10)))

        // Up to two favorites, launched fullscreen over the split.
        val favorites = AppLauncher.pickFavorites(AppLauncher.loadApps(this), 2)
        favorites.forEach { app ->
            rail.addView(appButton(app))
            rail.addView(spacer(dp(10)))
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
            textSize = 16f
            gravity = Gravity.CENTER
            text = currentClock()
        }
        speedView = TextView(this).apply {
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 11f
            gravity = Gravity.CENTER
            text = "-- km/h"
        }
        trackView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = ""
        }
        rail.addView(clockView)
        rail.addView(speedView)
        rail.addView(trackView)

        return rail
    }

    private fun buildAppGrid(pickerStage: Int): View {
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FA141518"))
            }
            isVerticalScrollBarEnabled = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val title = TextView(this).apply {
            text = when (pickerStage) {
                1 -> "Select App for LEFT Split"
                2 -> "Select App for RIGHT Split"
                else -> "All Apps"
            }
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        header.addView(title)
        container.addView(header)

        val apps = AppLauncher.loadApps(this)
        val columns = 5
        var currentRow: LinearLayout? = null

        apps.forEachIndexed { index, app ->
            if (index % columns == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(16))
                    }
                }
                container.addView(currentRow)
            }

            val appItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    when (pickerStage) {
                        1 -> {
                            chosenLeftPackage = app.packageName
                            updateOverlayState(OverlayState.SPLIT_PICKER_RIGHT)
                        }
                        2 -> {
                            val left = chosenLeftPackage ?: "com.google.android.apps.maps"
                            SplitScreenLauncher.launchCustomSplit(this@LauncherOverlayService, left, app.packageName)
                            updateOverlayState(OverlayState.COLLAPSED)
                        }
                        else -> {
                            SplitScreenLauncher.launchFullscreen(this@LauncherOverlayService, app.packageName)
                            updateOverlayState(OverlayState.COLLAPSED)
                        }
                    }
                }
            }

            val iconSize = dp(56)
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setImageDrawable(app.icon)
                val pad = dp(8)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(CARD_HI)
                }
            }

            val labelView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dp(8), 0, 0)
                }
                text = app.label
                setTextColor(Color.parseColor("#E8EAED"))
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            appItem.addView(iconView)
            appItem.addView(labelView)
            currentRow?.addView(appItem)
        }

        val remaining = apps.size % columns
        if (remaining > 0 && currentRow != null) {
            for (dummyIdx in 0 until (columns - remaining)) {
                val dummy = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                currentRow.addView(dummy)
            }
        }

        scrollView.addView(container)
        return scrollView
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
            setOnClickListener {
                SplitScreenLauncher.launchFullscreen(this@LauncherOverlayService, app.packageName)
                updateOverlayState(OverlayState.COLLAPSED)
            }
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

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
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
