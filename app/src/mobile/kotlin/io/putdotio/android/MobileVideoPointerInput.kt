package io.putdotio.android

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

internal fun Modifier.shareVideoPointerInputWithSiblings(): Modifier = this then VideoPointerSharingElement

private data object VideoPointerSharingElement : ModifierNodeElement<VideoPointerSharingNode>() {
    override fun create() = VideoPointerSharingNode()

    override fun update(node: VideoPointerSharingNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "shareVideoPointerInputWithSiblings"
    }
}

private class VideoPointerSharingNode : Modifier.Node(), PointerInputModifierNode {
    // Chrome handles controls and scrolling first; unconsumed taps reach the sibling video regions.
    override fun sharePointerInputWithSiblings() = true

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit

    override fun onCancelPointerInput() = Unit
}

internal suspend fun PointerInputScope.detectVideoTapGestures(
    onDoubleTap: (Offset) -> Unit,
    onTap: (Offset) -> Unit,
) {
    awaitEachGesture {
        // Native controls and scrolling consume in Main before the video observes Final.
        awaitFirstDown(pass = PointerEventPass.Final)
        val firstUp = awaitVideoTapRelease() ?: return@awaitEachGesture
        firstUp.consume()
        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            val earliest = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
            var down: PointerInputChange
            do {
                down = awaitFirstDown(pass = PointerEventPass.Final)
            } while (down.uptimeMillis < earliest)
            down
        }
        if (secondDown == null) {
            onTap(firstUp.position)
        } else {
            val secondUp = awaitVideoTapRelease()
            if (secondUp == null) {
                onTap(firstUp.position)
            } else {
                secondUp.consume()
                onDoubleTap(secondUp.position)
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitVideoTapRelease(): PointerInputChange? {
    while (true) {
        val changes = awaitPointerEvent(PointerEventPass.Final).changes
        if (changes.any { it.isConsumed || it.isOutOfBounds(size, extendedTouchPadding) }) return null
        if (changes.all { it.changedToUp() }) return changes.first()
    }
}
