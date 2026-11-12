package io.putdotio.android.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.putdotio.android.MobileSelection
import io.putdotio.android.MobileSelectionBar
import io.putdotio.android.MobileSelectionToolbar
import io.putdotio.android.MobileSelectionToolbarOffset
import io.putdotio.android.MobileToolbarTile
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.files.FilesItemId

internal const val MOBILE_TRASH_SELECT_TAG = "mobile-trash-select"
internal const val MOBILE_TRASH_SELECTION_BAR_TAG = "mobile-trash-selection-bar"
internal const val MOBILE_TRASH_SELECTION_TOOLBAR_TAG = "mobile-trash-selection-toolbar"
internal const val MOBILE_TRASH_SELECTION_RESTORE_TAG = "mobile-trash-selection-restore"
internal const val MOBILE_TRASH_SELECTION_DELETE_TAG = "mobile-trash-selection-delete"

@Stable
internal class TrashSelection(private val selection: MobileSelection = MobileSelection()) {
    fun selected(state: TrashState): Set<FilesItemId> =
        selection.selected(selectable(state)).mapTo(linkedSetOf(), ::FilesItemId)

    fun isActive(state: TrashState): Boolean = selection.isActive(selectable(state))

    fun start() = selection.start()

    fun start(state: TrashState, itemId: FilesItemId) {
        if (itemId !in selected(state)) selection.toggle(itemId.value)
        selection.start()
    }

    fun toggle(itemId: FilesItemId) = selection.toggle(itemId.value)

    fun selectAll(state: TrashState) = selection.selectAll(selectable(state))

    fun end() = selection.end()

    fun reconcile(state: TrashState) {
        if (state.content is TrashContent.Loaded) selection.retain(selectable(state))
    }

    private fun selectable(state: TrashState): Set<Long> =
        (state.content as? TrashContent.Loaded)?.items.orEmpty()
            .filter { it.id.value > 0L }
            .mapTo(linkedSetOf()) { it.id.value }

    companion object {
        val Saver: Saver<TrashSelection, Any> = Saver(
            save = { with(MobileSelection.Saver) { save(it.selection) } },
            restore = { saved -> MobileSelection.Saver.restore(saved)?.let(::TrashSelection) },
        )
    }
}

@Composable
internal fun TrashSelectionEffects(state: TrashState, selection: TrashSelection) {
    LaunchedEffect(selection, state.content) { selection.reconcile(state) }
    DisposableEffect(selection) { onDispose(selection::end) }
    BackHandler(enabled = selection.isActive(state), onBack = selection::end)
}

@Composable
internal fun rememberTrashSelection(key: Any?): TrashSelection =
    rememberSaveable(key, saver = TrashSelection.Saver) { TrashSelection() }

internal fun trashSelectionBar(
    route: String?,
    state: TrashState?,
    selection: TrashSelection,
): (@Composable () -> Unit)? =
    if (route == MOBILE_TRASH_ROUTE && state != null && selection.isActive(state)) {
        {
            MobileSelectionBar(
                count = selection.selected(state).size,
                onSelectAll = { selection.selectAll(state) },
                onClear = selection::end,
                modifier = Modifier.testTag(MOBILE_TRASH_SELECTION_BAR_TAG),
            )
        }
    } else {
        null
    }

@Composable
internal fun MobileTrashSelectionToolbar(
    state: TrashState,
    selected: Set<FilesItemId>,
    onEvent: (TrashEvent) -> Boolean,
    onSent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = pluralStringResource(R.plurals.mobile_trash_selection_actions, selected.size, selected.size)
    MobileSelectionToolbar(
        modifier
            .padding(bottom = MobileSelectionToolbarOffset)
            .testTag(MOBILE_TRASH_SELECTION_TOOLBAR_TAG)
            .semantics { contentDescription = label },
    ) {
        MobileToolbarTile(
            icon = R.drawable.ic_ph_arrow_counter_clockwise,
            label = stringResource(R.string.mobile_trash_restore),
            enabled = state.canRestoreAll(selected),
            onClick = { if (onEvent(TrashEvent.RestoreSelected(selected))) onSent() },
            modifier = Modifier.testTag(MOBILE_TRASH_SELECTION_RESTORE_TAG),
        )
        MobileToolbarTile(
            icon = R.drawable.ic_ph_trash,
            label = stringResource(R.string.mobile_trash_delete),
            color = PutioDesignTokens.redTextSecondary,
            enabled = state.canDeleteAll(selected),
            onClick = { onEvent(TrashEvent.SelectDeleteSelected(selected)) },
            modifier = Modifier.testTag(MOBILE_TRASH_SELECTION_DELETE_TAG),
        )
    }
}

internal fun ((TrashEvent) -> Boolean).endingSelectionOn(
    action: TrashDestructiveAction,
    selection: TrashSelection,
): (TrashEvent) -> Boolean = { event ->
    invoke(event).also { sent ->
        if (sent && event is TrashEvent.ConfirmAction && action is TrashAction.DeleteSelected) selection.end()
    }
}
