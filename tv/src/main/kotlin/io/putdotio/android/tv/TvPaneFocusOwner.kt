package io.putdotio.android.tv

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.first

/**
 * Which section of a pane focus should return to. Sections register themselves while
 * they hold focus and hand the target back to the pane's home section when they leave
 * composition. `home` is read at that moment, so it may depend on the pane's content.
 */
internal class TvPaneFocusOwner(
    private val entryTarget: MutableState<FocusRequester>,
    private val paneHasFocus: State<Boolean>,
    private val lastSection: MutableState<Long?> = mutableStateOf(null),
    private val onEnter: (FocusRequester) -> Unit = {},
    private val home: () -> FocusRequester,
) {
    private val sections = mutableMapOf<Long, FocusRequester>()

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

    private val refocusRequests = mutableIntStateOf(0)

    /**
     * Moves focus to the entry point a frame after a section that held it left composition,
     * unless the user has left the pane or [blocked] holds focus over it.
     */
    @Composable
    fun RefocusAfterLeave(blocked: State<Boolean>) {
        val requests = refocusRequests.intValue
        LaunchedEffect(requests) {
            if (requests == 0) return@LaunchedEffect
            withFrameNanos {}
            if (paneHasFocus.value && !blocked.value) focusEntry()
        }
    }

    /** Records that the section behind [requester] holds focus, making it the entry point. */
    fun enter(requester: FocusRequester, section: Long? = null) {
        entryTarget.value = requester
        if (section != null) lastSection.value = section
        onEnter(requester)
    }

    fun register(section: Long, requester: FocusRequester) {
        sections[section] = requester
    }

    fun unregister(section: Long, requester: FocusRequester) {
        if (sections[section] === requester) sections.remove(section)
    }

    fun restoreLastSection() {
        sections[lastSection.value]?.let { entryTarget.value = it }
    }

    /**
     * Hands the entry point to [fallback] (or the pane's home) when the leaving section holds
     * it, and requests a refocus if the pane had focus.
     */
    fun leave(requester: FocusRequester, fallback: (() -> FocusRequester)?) {
        if (entryTarget.value === requester) {
            entryTarget.value = fallback?.invoke() ?: home()
            if (paneHasFocus.value) refocusRequests.intValue += 1
        }
    }

    fun focusEntry() = entryTarget.value.requestFocus()

    val hasFocus: Boolean get() = paneHasFocus.value
}

internal class TvPickedRow {
    var id: Long? = null
        private set

    fun pick(rowId: Long) {
        id = rowId
    }

    fun forget() {
        id = null
    }

    fun keptOnlyBy(rows: FocusRequester): (FocusRequester) -> Unit = { entered -> if (entered !== rows) forget() }

    companion object {
        val Saver: Saver<TvPickedRow, Long> = Saver(
            save = { it.id },
            restore = { id -> TvPickedRow().apply { pick(id) } },
        )
    }
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
    val section = currentCompositeKeyHashCode
    DisposableEffect(requester) {
        owner.register(section, requester)
        onDispose {
            owner.unregister(section, requester)
            owner.leave(requester, fallback)
        }
    }
    return onFocusChanged { if (it.hasFocus) owner.enter(requester, section) }
}

/**
 * Lets a control hand focus back to its pane when it leaves composition while focused; the pane
 * runs [Refocus] to place focus on its entry point a frame later, unless the user has left the
 * pane or a dialog blocks it. A focused node reports losing focus before its effects dispose, so
 * the pane checks a frame later that the control is gone.
 */
internal class TvFocusHandBack {
    private val requests = mutableIntStateOf(0)
    private var lost: TvHandBackControl? = null

    fun lose(control: TvHandBackControl) {
        lost = control
        requests.intValue += 1
    }

    @Composable
    fun Refocus(paneHasFocus: State<Boolean>, blocked: State<Boolean>, target: State<FocusRequester>) {
        val pending = requests.intValue
        LaunchedEffect(pending) {
            if (pending == 0) return@LaunchedEffect
            withFrameNanos {}
            val control = lost ?: return@LaunchedEffect
            lost = null
            if (!control.composed && paneHasFocus.value && !blocked.value) target.value.requestFocus()
        }
    }
}

internal class TvHandBackControl {
    var focused = false
    var composed = false
}

@Composable
internal fun Modifier.handsBackFocus(handBack: TvFocusHandBack): Modifier {
    val control = remember { TvHandBackControl() }
    DisposableEffect(control) {
        control.composed = true
        onDispose { control.composed = false }
    }
    return onFocusChanged {
        if (control.focused && !it.isFocused) handBack.lose(control)
        control.focused = it.isFocused
    }
}

internal fun Modifier.focusRequesterIf(condition: Boolean, requester: FocusRequester): Modifier =
    if (condition) focusRequester(requester) else this

/**
 * Scrolls to [index] unless the item keyed [key] is composed, then waits until it is: a lazy
 * row that is not composed has no focus requester to answer.
 */
internal suspend fun LazyListState.bringIntoComposition(key: Any, index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.key == key }) scrollToItem(index)
    snapshotFlow { layoutInfo.visibleItemsInfo.any { it.key == key } }.first { it }
}

/**
 * Runs as a pane's `LaunchedEffect(dialogOpen)`. A closing dialog's window takes focus with it,
 * so a frame later the pane's entry target takes it back, unless the user left the pane meanwhile.
 */
internal suspend fun refocusAfterDialog(
    dialogOpen: Boolean,
    dialogWasOpen: MutableState<Boolean>,
    paneHasFocus: State<Boolean>,
    entryTarget: State<FocusRequester>,
) {
    val closing = dialogWasOpen.value && !dialogOpen
    dialogWasOpen.value = dialogOpen
    if (!closing || !paneHasFocus.value) return
    withFrameNanos {}
    if (paneHasFocus.value) entryTarget.value.requestFocus()
}

internal val LocalTvPaneReleased = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

@Composable
internal fun rememberTvPaneHasFocus(): MutableState<Boolean> {
    val released = LocalTvPaneReleased.current
    return remember(released) { TvPaneHasFocus(released) }
}

private class TvPaneHasFocus(private val released: State<Boolean>) : MutableState<Boolean> {
    private val own = mutableStateOf(true)

    override var value: Boolean
        get() = own.value && !released.value
        set(value) {
            own.value = value
        }

    override fun component1(): Boolean = value

    override fun component2(): (Boolean) -> Unit = { value = it }
}
