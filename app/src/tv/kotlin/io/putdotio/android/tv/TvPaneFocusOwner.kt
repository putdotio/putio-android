package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
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

    /**
     * Makes the requester the next entry point without moving focus, but only while
     * [from] still is: a section that has since taken the target keeps it.
     */
    fun claim(requester: FocusRequester, from: FocusRequester) {
        if (entryTarget.value === from) entryTarget.value = requester
    }

    private val mutableRefocusRequests = mutableIntStateOf(0)

    /**
     * Bumps when a section that held focus left composition, so the pane can move focus
     * to the new entry point once the frame has settled; a pane that does not observe it
     * leaves focus where the window puts it.
     */
    val refocusRequests: State<Int> = mutableRefocusRequests

    /** Records that the section behind [requester] holds focus, making it the entry point. */
    fun enter(requester: FocusRequester) {
        entryTarget.value = requester
    }

    /**
     * Hands the entry point to [fallback] (or the pane's home) when the leaving section holds
     * it, and requests a refocus if the pane had focus.
     */
    fun leave(requester: FocusRequester, fallback: (() -> FocusRequester)?) {
        if (entryTarget.value === requester) {
            entryTarget.value = fallback?.invoke() ?: home()
            if (paneHasFocus.value) mutableRefocusRequests.intValue += 1
        }
    }

    fun focusEntry() = entryTarget.value.requestFocus()

    /** Whether D-pad focus belongs to the pane. */
    val hasFocus: Boolean get() = paneHasFocus.value
}

/**
 * The row a pane last opened something from: playback or Files replaces the pane, and the
 * row takes focus again when the pane next mounts, as Files does for its rows. Owned by the
 * session so it survives the pane being disposed. Plain, not snapshot state.
 */
internal class TvPickedRow {
    private var id: Long? = null

    fun pick(rowId: Long) {
        id = rowId
    }

    /** The row to focus on this mount; read once, so a later visit starts as usual. */
    fun take(): Long? = id.also { id = null }
}

/**
 * Tracks focus for a section of [owner]'s pane; the requester itself is attached where focus
 * should land. When the section leaves composition while it is the entry point, [fallback] (or
 * the pane's home) becomes the entry point and a refocus is requested if the pane had focus.
 */
@Composable
internal fun Modifier.paneSection(
    owner: TvPaneFocusOwner,
    requester: FocusRequester,
    fallback: (() -> FocusRequester)? = null,
): Modifier {
    DisposableEffect(requester) {
        onDispose { owner.leave(requester, fallback) }
    }
    return onFocusChanged { if (it.hasFocus) owner.enter(requester) }
}
