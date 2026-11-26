package io.putdotio.android.files

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput

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
