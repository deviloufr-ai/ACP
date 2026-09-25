package com.openauto.dash

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openauto.dash.link.CallCommand
import com.openauto.dash.link.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * The phone's calls on the head unit: a card at the top of the screen with
 * who is calling and Answer / Decline, then a slim bar with the call's
 * duration and Hang up. It is its own overlay window, so it shows over
 * whatever app fills the screen, and keeps running while the launcher is in
 * the background. Without "display over other apps" it falls back to a popup
 * inside the launcher ([PhoneCallHost]).
 */

private const val TAG = "PhoneCall"

object PhoneCallOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: CallWindow? = null

    private val _showing = MutableStateFlow(false)
    /** True while the overlay window is up (the launcher's popup then stays away). */
    val showing: StateFlow<Boolean> = _showing

    fun start(context: Context) {
        if (started) return
        started = true
        // Strings in the language picked in the launcher, not the system's.
        val app = AppLanguage.wrap(context.applicationContext)
        scope.launch {
            PhoneLink.call.collect { call ->
                if (call != null && !Settings.canDrawOverlays(app)) PipAnchor.grantOverlayPermission(app)
                if (call != null && Settings.canDrawOverlays(app)) {
                    val w = window ?: CallWindow(app).also { window = it }
                    _showing.value = w.show()
                } else {
                    window?.remove()
                    _showing.value = false
                }
            }
        }
    }
}

/** The overlay window; its composition follows [PhoneLink.call] by itself. */
private class CallWindow(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun show(): Boolean {
        if (view != null) return true
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable (the buttons), but never takes the keyboard or the back key.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * context.resources.displayMetrics.density).toInt()
            title = "Dashwheel call"
        }
        val o = OverlayOwner()
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                OpenAutoDashTheme {
                    val call by PhoneLink.call.collectAsState()
                    call?.let { CallCard(it) }
                }
            }
        }
        return runCatching { wm.addView(v, lp) }
            .onSuccess { view = v; owner = o }
            .onFailure { Log.w(TAG, "could not add the call window", it); o.destroy() }
            .isSuccess
    }

    fun remove() {
        val v = view ?: return
        view = null
        runCatching { wm.removeViewImmediate(v) }.onFailure { Log.w(TAG, "remove failed", it) }
        owner?.destroy()
        owner = null
    }
}

/**
 * Keeps the overlay's composition running on its own: tied to the launcher's
 * activity it would pause whenever another app is in front, exactly when a
 * call card is needed most.
 */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** The launcher's own call popup, for when the overlay window isn't allowed. */
@Composable
internal fun PhoneCallHost() {
    val call by PhoneLink.call.collectAsState()
    val overlay by PhoneCallOverlay.showing.collectAsState()
    val c = call ?: return
    if (overlay) return
    val top = with(LocalDensity.current) { 24.dp.roundToPx() }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, top), properties = PopupProperties(focusable = false)) {
        CallCard(c)
    }
}

private val Answer = Color(0xFF2E9D4F)
private val HangUp = Color(0xFFD93A3A)

@Composable
internal fun CallCard(call: PhoneCall) {
    val ringing = call.phase == CallState.Phase.RINGING
    val title = call.name ?: call.number ?: stringResource(R.string.phone_call_unknown)
    SolidCard {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = if (ringing) 16.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallerAvatar(call, if (ringing) 64.dp else 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.widthIn(min = 160.dp, max = 340.dp)) {
                Text(
                    title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (ringing) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
                )
                if (ringing) {
                    val subtitle = stringResource(R.string.phone_call_incoming)
                    Text(
                        if (call.name != null && call.number != null) "$subtitle · ${call.number}" else subtitle,
                        color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    CallDuration(call.answeredAt)
                }
                if (!call.canControl) {
                    Text(stringResource(R.string.phone_call_no_control), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (call.canControl) {
                Spacer(Modifier.width(16.dp))
                if (ringing) {
                    CallButton(Icons.Filled.CallEnd, HangUp, stringResource(R.string.phone_call_decline), 64.dp) {
                        PhoneLink.callCommand(CallCommand.Action.DECLINE)
                    }
                    Spacer(Modifier.width(14.dp))
                    CallButton(Icons.Filled.Call, Answer, stringResource(R.string.phone_call_answer), 64.dp) {
                        PhoneLink.callCommand(CallCommand.Action.ANSWER)
                    }
                } else {
                    CallButton(Icons.Filled.CallEnd, HangUp, stringResource(R.string.phone_call_hang_up), 52.dp) {
                        PhoneLink.callCommand(CallCommand.Action.HANG_UP)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallerAvatar(call: PhoneCall, size: Dp) {
    val photo = call.photo
    if (photo != null) {
        Image(photo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        val initial = call.name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        Box(Modifier.size(size).clip(CircleShape).background(DashColors.CardHi), contentAlignment = Alignment.Center) {
            if (initial != null) Text(initial.toString(), color = DashColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
            else Icon(Icons.Filled.Call, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size / 2))
        }
    }
}

/** m:ss (h:mm:ss past an hour) since [answeredAt], ticking. */
@Composable
private fun CallDuration(answeredAt: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(answeredAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val total = ((now - answeredAt) / 1000).coerceAtLeast(0)
    val text = if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
    Text(text, color = DashColors.Good, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CallButton(icon: ImageVector, color: Color, label: String, size: Dp, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .clickable(role = Role.Button, onClickLabel = label) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(size * 0.45f))
    }
}
