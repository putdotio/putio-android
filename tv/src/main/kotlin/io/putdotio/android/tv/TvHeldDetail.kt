package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo

@Stable
internal class TvHeldDetail<T>(initial: T) {
    var shown: T by mutableStateOf(initial)
        private set
    private val heldState = mutableStateOf(false)
    val held: State<Boolean> get() = heldState
    private var focused: T = initial

    fun focus(row: T) {
        focused = row
        if (!heldState.value) shown = row
    }

    fun onKey(event: KeyEvent) {
        if (event.isHeldUpOrDown()) heldState.value = true else settle()
    }

    fun settle() {
        heldState.value = false
        shown = focused
    }
}

@Composable
internal fun <T> rememberTvHeldDetail(vararg keys: Any?, initial: () -> T): TvHeldDetail<T> {
    val detail = remember(*keys) { TvHeldDetail(initial()) }
    val window = LocalWindowInfo.current
    LaunchedEffect(detail, window) {
        snapshotFlow { window.isWindowFocused }.collect { focused -> if (!focused) detail.settle() }
    }
    return detail
}

internal fun Modifier.heldDetail(detail: TvHeldDetail<*>): Modifier =
    onFocusChanged { if (!it.hasFocus) detail.settle() }
        .onPreviewKeyEvent {
            detail.onKey(it)
            false
        }
