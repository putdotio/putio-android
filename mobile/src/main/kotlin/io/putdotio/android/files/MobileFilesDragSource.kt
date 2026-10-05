package io.putdotio.android.files

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Starts a drag out of a row: with a mouse or touchpad as soon as the pressed pointer moves, with
 * touch or a stylus only when it moves after a long press, as Android's drag-and-drop guidance asks.
 * It only watches the pointer, so taps, the long-press sheet and scrolling work as before: a touch
 * that moves before the long press is a scroll and never drags. The gesture is set up once, so
 * [onDragStart] must read anything that changes through state.
 */
internal fun Modifier.mobileFileDragSource(enabled: Boolean, onDragStart: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val mouse = down.type == PointerType.Mouse
                if (mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                val armedAt = down.uptimeMillis + if (mouse) 0L else viewConfiguration.longPressTimeoutMillis
                var change = down
                // Follow the press until it lifts or leaves the touch slop; only then can it be a drag.
                do {
                    change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        ?: return@awaitEachGesture
                    val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                } while (change.pressed && !moved)
                if (change.pressed && change.uptimeMillis >= armedAt) {
                    change.consume()
                    onDragStart()
                }
            }
        }
    }
