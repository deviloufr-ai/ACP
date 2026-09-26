package com.openauto.dash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The bottom bar's auto-hide (Settings › Look): once the bar has sat unused
 * for the chosen number of seconds it slides away and leaves a slim handle
 * that says to swipe up; a swipe up on the handle (or a tap) brings it back.
 */

/** Longest delay the Look settings offer before the bar hides, in seconds. */
internal const val MAX_BAR_HIDE_SECONDS = 20

/** A swipe up always leaves the bar this long to be used, even with a shorter delay (0 s included). */
internal const val BAR_REVEAL_GRACE_MS = 3_000L

/** How long the "swipe up" words stay beside the handle after the bar hides; the arrow and the pill stay. */
private const val HANDLE_WORDS_MS = 4_000L

private const val BAR_SLIDE_MS = 250

/**
 * How long the bar waits, unused, before it hides: [seconds] (0 to
 * [MAX_BAR_HIDE_SECONDS]), but never less than [BAR_REVEAL_GRACE_MS] right
 * after a swipe brought it back, or a short delay would take it away again
 * before the finger could reach it.
 */
internal fun barHideDelayMs(seconds: Int, afterReveal: Boolean): Long {
    val ms = seconds.coerceIn(0, MAX_BAR_HIDE_SECONDS) * 1_000L
    return if (afterReveal) maxOf(ms, BAR_REVEAL_GRACE_MS) else ms
}

internal object BarAutoHide {
    /**
     * Menus open from the bar. The bar stays while any is: hiding it would
     * take the menu anchored on it along.
     */
    var openMenus by mutableIntStateOf(0)
}

/**
 * The bottom bar ([bar]), hiding itself after [hideSeconds] unused seconds
 * while [enabled]. A finger on the bar, a menu open from it, or [held] (the
 * dashboard being arranged, Settings or the app drawer open) keeps it up; the
 * wait starts again once they let go.
 *
 * The bar slides out without shrinking and the handle takes its place in one
 * step: the pages above re-measure once per change, not on every frame of it,
 * and a docked app window is not dragged along by an animation.
 */
@Composable
internal fun AutoHidingBar(
    enabled: Boolean,
    hideSeconds: Int,
    held: Boolean,
    modifier: Modifier = Modifier,
    bar: @Composable () -> Unit
) {
    val visible = remember { MutableTransitionState(true) }
    var pressing by remember { mutableStateOf(false) }
    // Bumped when a finger leaves the bar: the wait starts over.
    var touches by remember { mutableIntStateOf(0) }
    // The bar is up because of a swipe and has not been touched since.
    var afterReveal by remember { mutableStateOf(false) }
    val hold = held || pressing || BarAutoHide.openMenus > 0

    LaunchedEffect(enabled) { if (!enabled) visible.targetState = true }
    LaunchedEffect(enabled, hold, touches, hideSeconds, visible.targetState) {
        if (enabled && !hold && visible.targetState) {
            delay(barHideDelayMs(hideSeconds, afterReveal))
            visible.targetState = false
        }
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(tween(BAR_SLIDE_MS)) { it } + fadeIn(tween(BAR_SLIDE_MS)),
            exit = slideOutVertically(tween(BAR_SLIDE_MS)) { it } + fadeOut(tween(BAR_SLIDE_MS))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Watches, never consumes: the bar's buttons and its page swipe work as before.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val down = event.changes.any { it.pressed }
                                if (down != pressing) {
                                    pressing = down
                                    afterReveal = false
                                    if (!down) touches++
                                }
                            }
                        }
                    }
            ) { bar() }
        }
        if (visible.isIdle && !visible.currentState) {
            BarHandle(
                onReveal = {
                    afterReveal = true
                    visible.targetState = true
                }
            )
        }
    }
}

/**
 * Where the hidden bar was: an arrow and a pill, and for a few seconds the
 * words "swipe up" beside the arrow, which nudges up three times as the bar
 * goes. A swipe up anywhere on the strip, or a tap, brings the bar back.
 */
@Composable
private fun BarHandle(onReveal: () -> Unit) {
    val tap = rememberTapFeedback()
    var words by remember { mutableStateOf(true) }
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(3) {
            nudge.animateTo(1f, tween(260))
            nudge.animateTo(0f, tween(260))
        }
    }
    LaunchedEffect(Unit) {
        delay(HANDLE_WORDS_MS)
        words = false
    }
    val label = stringResource(R.string.dash_bar_show)
    // The swipe detector outlives recompositions: it calls the latest ones.
    val reveal by rememberUpdatedState {
        tap()
        onReveal()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                val threshold = 16.dp.toPx()
                var dragged = 0f
                var revealed = false
                detectVerticalDragGestures(
                    onDragStart = {
                        dragged = 0f
                        revealed = false
                    }
                ) { change, dy ->
                    change.consume()
                    dragged += dy
                    // Up as soon as the swipe clearly goes up, without waiting for the finger to lift.
                    if (!revealed && dragged <= -threshold) {
                        revealed = true
                        reveal()
                    }
                }
            }
            .clickable(onClickLabel = label, role = Role.Button) { reveal() }
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer { translationY = -nudge.value * 5.dp.toPx() }
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = DashColors.TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            AnimatedVisibility(words, enter = fadeIn(), exit = fadeOut() + shrinkHorizontally()) {
                Text(
                    stringResource(R.string.dash_bar_swipe_up_hint),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp, end = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .size(width = 56.dp, height = 4.dp)
                .clip(DashShape.Pill)
                .background(DashColors.TextSecondary.copy(alpha = 0.55f))
        )
    }
}
