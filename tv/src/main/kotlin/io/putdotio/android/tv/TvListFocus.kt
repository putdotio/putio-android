package io.putdotio.android.tv

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

internal class TvFocusedItem {
    var key: Any? = null
}

internal class TvItemRequesters {
    private val requesters = HashMap<Any, FocusRequester>()

    fun of(key: Any): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun firstComposed(list: LazyListState, takes: (Any) -> Boolean): FocusRequester =
        list.layoutInfo.visibleItemsInfo.firstOrNull { takes(it.key) }?.let { of(it.key) } ?: FocusRequester.Default
}

internal fun Modifier.tracksFocusedItem(focused: TvFocusedItem, key: Any): Modifier =
    onFocusChanged {
        if (it.isFocused) {
            focused.key = key
        } else if (focused.key == key) {
            focused.key = null
        }
    }

internal fun Modifier.keepsHeldFocusInView(list: LazyListState, focusedKey: () -> Any?): Modifier =
    onPreviewKeyEvent { it.outrunsScroll(list, focusedKey()) }

internal fun Modifier.keepsHeldFocusInView(list: LazyListState, focused: TvFocusedItem): Modifier =
    keepsHeldFocusInView(list) { focused.key }

internal fun KeyEvent.isHeldUpOrDown(): Boolean = isHeldAlong(Orientation.Vertical)

private fun KeyEvent.isHeldAlong(orientation: Orientation): Boolean {
    if (type != KeyEventType.KeyDown || nativeKeyEvent.repeatCount == 0) return false
    return when (orientation) {
        Orientation.Vertical -> key == Key.DirectionDown || key == Key.DirectionUp
        Orientation.Horizontal -> key == Key.DirectionRight || key == Key.DirectionLeft
    }
}

internal fun KeyEvent.outrunsScroll(list: LazyListState, focusedKey: Any?): Boolean {
    val layout = list.layoutInfo
    return focusedKey != null &&
        isHeldAlong(layout.orientation) &&
        (list.isScrollInProgress || layout.hides(focusedKey))
}

private fun LazyListLayoutInfo.hides(key: Any): Boolean {
    val item = visibleItemsInfo.firstOrNull { it.key == key } ?: return true
    val start = viewportStartOffset
    val end = viewportEndOffset
    return item.size < end - start && (item.offset < start || item.offset + item.size > end)
}
