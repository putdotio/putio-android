package io.putdotio.android.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Stable
internal class MobileViewerZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(IntSize.Zero)
    var content by mutableStateOf<IntSize?>(null)

    val zoomed by derivedStateOf { scale > 1f }

    fun transform(zoomChange: Float, centroid: Offset, pan: Offset): Offset {
        val next = (scale * zoomChange).coerceIn(1f, VIEWER_ZOOM_MAX)
        val fromCenter = centroid - center()
        val wanted = fromCenter - (fromCenter - offset) * (next / scale) + pan
        scale = next
        offset = clamp(wanted)
        return wanted - offset
    }

    fun toggle(at: Offset) {
        if (zoomed) {
            scale = 1f
            offset = Offset.Zero
        } else {
            transform(VIEWER_ZOOM_DOUBLE_TAP, at, Offset.Zero)
        }
    }

    private fun center() = Offset(viewport.width / 2f, viewport.height / 2f)

    private fun clamp(wanted: Offset): Offset {
        val shown = content ?: viewport
        val maxX = ((shown.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((shown.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(wanted.x.coerceIn(-maxX, maxX), wanted.y.coerceIn(-maxY, maxY))
    }
}

internal fun Modifier.viewerZoom(zoom: MobileViewerZoom?, onOverflow: (Offset) -> Unit = {}): Modifier {
    if (zoom == null) return this
    return clipToBounds()
        .onSizeChanged { zoom.viewport = it }
        .pointerInput(zoom) { detectTapGestures(onDoubleTap = zoom::toggle) }
        .pointerInput(zoom) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed > 1 || (pressed == 1 && zoom.zoomed)) {
                        val overflow = zoom.transform(
                            zoomChange = event.calculateZoom(),
                            centroid = event.calculateCentroid(useCurrent = true),
                            pan = event.calculatePan(),
                        )
                        if (overflow != Offset.Zero) onOverflow(overflow / zoom.scale)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .graphicsLayer {
            scaleX = zoom.scale
            scaleY = zoom.scale
            translationX = zoom.offset.x
            translationY = zoom.offset.y
        }
}

internal const val VIEWER_ZOOM_MAX = 4f
internal const val VIEWER_ZOOM_DOUBLE_TAP = 2.5f
