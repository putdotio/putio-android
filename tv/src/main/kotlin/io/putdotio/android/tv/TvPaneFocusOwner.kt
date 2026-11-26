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

internal class TvPaneFocusOwner(
    private val entryTarget: MutableState<FocusRequester>,
    private val paneHasFocus: State<Boolean>,
    private val lastSection: MutableState<Long?> = mutableStateOf(null),
    private val onEnter: (FocusRequester) -> Unit = {},
    private val home: () -> FocusRequester,
) {
    private val sections = mutableMapOf<Long, FocusRequester>()

    fun owns(requester: FocusRequester): Boolean = paneHasFocus.value && entryTarget.value === requester

    fun focusHome() = home().requestFocus()

    fun claim(requester: FocusRequester, from: FocusRequester) {
        if (entryTarget.value === from) entryTarget.value = requester
    }

    private val refocusRequests = mutableIntStateOf(0)

    @Composable
    fun RefocusAfterLeave(blocked: State<Boolean>) {
        val requests = refocusRequests.intValue
        LaunchedEffect(requests) {
            if (requests == 0) return@LaunchedEffect
            withFrameNanos {}
            if (paneHasFocus.value && !blocked.value) focusEntry()
        }
    }

    fun enter(requester: FocusRequester, section: Long? = null) {
        restorePending = false
        entryTarget.value = requester
        if (section != null) lastSection.value = section
        onEnter(requester)
    }

    fun register(section: Long, requester: FocusRequester) {
        sections[section] = requester
        if (restorePending && section == lastSection.value) {
            restorePending = false
            entryTarget.value = requester
        }
    }

    fun unregister(section: Long, requester: FocusRequester) {
        if (sections[section] === requester) sections.remove(section)
    }

    private var restorePending = false

    fun restoreLastSection() {
        val section = sections[lastSection.value]
        if (section != null) entryTarget.value = section
        restorePending = section == null && lastSection.value != null
    }

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

internal suspend fun LazyListState.bringIntoComposition(key: Any, index: Int) {
    if (!composes(key)) scrollToItem(index)
    snapshotFlow { composes(key) }.first { it }
}

internal fun LazyListState.composes(key: Any?): Boolean =
    key != null && layoutInfo.visibleItemsInfo.any { it.key == key }

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
    val target = entryTarget.value
    val placeable = target != FocusRequester.Cancel && target != FocusRequester.Default
    if (paneHasFocus.value && placeable) target.requestFocus()
}

internal val LocalTvPaneReleased = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

internal val LocalTvTabFocus = staticCompositionLocalOf<FocusRequester?> { null }

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
