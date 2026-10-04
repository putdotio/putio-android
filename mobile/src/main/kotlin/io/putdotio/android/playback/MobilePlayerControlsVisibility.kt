package io.putdotio.android.playback

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.Player as Media3Player

internal fun Modifier.observePlayerControlInteraction(
    onInteractionChanged: (Boolean) -> Unit,
    onActivity: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            var wasPressed = false
            while (true) {
                val isPressed =
                    awaitPointerEvent(PointerEventPass.Initial)
                        .changes
                        .any { it.pressed }
                if (isPressed && !wasPressed) onActivity()
                onInteractionChanged(isPressed)
                wasPressed = isPressed
            }
        }
    }

internal fun Modifier.observePlayerControlKeyActivity(onActivity: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) onActivity()
        false
    }

internal fun Modifier.observeControlNavigation(
    onPointerNavigation: () -> Unit,
    onKeyboardNavigation: () -> Unit,
): Modifier =
    observePlayerControlInteraction(
        onInteractionChanged = { if (it) onPointerNavigation() },
        onActivity = {},
    ).observePlayerControlKeyActivity(onKeyboardNavigation)

internal fun Media3Player.controlsShouldAutoHide(
    controlsVisible: Boolean,
    pointerInteracting: Boolean,
    keyboardNavigationActive: Boolean,
    menuOpen: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean =
    controlsVisible &&
        playWhenReady &&
        playbackState != Media3Player.STATE_ENDED &&
        !pointerInteracting &&
        !keyboardNavigationActive &&
        !menuOpen &&
        !touchExplorationEnabled

internal fun controlsVisibleForTouchExploration(
    controlsVisible: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean = controlsVisible || touchExplorationEnabled

internal fun controlsVisibleForPlaybackState(
    controlsVisible: Boolean,
    playbackState: Int,
): Boolean = controlsVisible || playbackState == Media3Player.STATE_ENDED

internal fun controlsVisibleAfterTap(
    controlsVisible: Boolean,
    playbackState: Int,
    touchExplorationEnabled: Boolean,
): Boolean =
    if (playbackState == Media3Player.STATE_ENDED || touchExplorationEnabled) {
        true
    } else {
        !controlsVisible
    }
