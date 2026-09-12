package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Which section of a pane focus should return to. Sections register themselves while
 * they hold focus and hand the target back to the pane's home section when they leave
 * composition. `home` is read at that moment, so it may depend on the pane's content.
 */
internal class TvPaneFocusOwner(
    private val entryTarget: MutableState<FocusRequester>,
    private val paneHasFocus: State<Boolean>,
    private val home: () -> FocusRequester,
) {
    /** True while the pane holds focus and this section was the last to have it. */
    fun owns(requester: FocusRequester): Boolean = paneHasFocus.value && entryTarget.value === requester

    fun focusHome() = home().requestFocus()

    /** Tracks focus for a section; the requester itself is attached where focus should land. */
    @Composable
    fun section(requester: FocusRequester): Modifier {
        DisposableEffect(requester) {
            onDispose { if (entryTarget.value === requester) entryTarget.value = home() }
        }
        return Modifier.onFocusChanged { if (it.hasFocus) entryTarget.value = requester }
    }
}
