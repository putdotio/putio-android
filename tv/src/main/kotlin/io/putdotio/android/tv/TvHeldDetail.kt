package io.putdotio.android.tv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent

@Stable
internal class TvHeldDetail<T>(initial: T) {
    var shown: T by mutableStateOf(initial)
        private set
    private var focused: T = initial
    private var held = false

    fun focus(row: T) {
        focused = row
        if (!held) shown = row
    }

    fun onKey(event: KeyEvent) {
        if (event.isHeldUpOrDown()) held = true else settle()
    }

    fun settle() {
        held = false
        shown = focused
    }
}

internal fun Modifier.heldDetail(detail: TvHeldDetail<*>): Modifier =
    onFocusChanged { if (!it.hasFocus) detail.settle() }
        .onPreviewKeyEvent {
            detail.onKey(it)
            false
        }

internal fun Modifier.tracksHeldUpOrDown(held: MutableState<Boolean>): Modifier =
    onFocusChanged { if (!it.hasFocus) held.value = false }
        .onPreviewKeyEvent {
            held.value = it.isHeldUpOrDown()
            false
        }
