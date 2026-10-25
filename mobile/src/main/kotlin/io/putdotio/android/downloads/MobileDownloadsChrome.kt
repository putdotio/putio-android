package io.putdotio.android.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileSelection
import io.putdotio.android.MobileSelectionBar
import io.putdotio.android.MobileSelectionToolbar
import io.putdotio.android.MobileTabTitle
import io.putdotio.android.MobileToolbarTile
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.neutralTextButtonColors

internal const val MOBILE_DOWNLOADS_OVERFLOW_TAG = "mobile-downloads-overflow"
internal const val MOBILE_DOWNLOADS_SELECT_TAG = "mobile-downloads-select"
internal const val MOBILE_DOWNLOADS_CONCURRENCY_TAG = "mobile-downloads-concurrency"
internal const val MOBILE_DOWNLOADS_SELECTION_BAR_TAG = "mobile-downloads-selection-bar"
internal const val MOBILE_DOWNLOADS_SELECTION_PAUSE_TAG = "mobile-downloads-selection-pause"
internal const val MOBILE_DOWNLOADS_SELECTION_RESUME_TAG = "mobile-downloads-selection-resume"
internal const val MOBILE_DOWNLOADS_SELECTION_DELETE_TAG = "mobile-downloads-selection-delete"

/**
 * Downloads selection, shared by the top bar and the list. Select opens an empty selection and a
 * long press opens one with its row; rows that leave the store, or start deleting, leave it.
 */
@Stable
internal class DownloadsSelection(private val selection: MobileSelection = MobileSelection()) {
    fun selected(state: DownloadsState): Set<FilesItemId> =
        selection.selected(selectable(state)).mapTo(linkedSetOf(), ::FilesItemId)

    fun isActive(state: DownloadsState): Boolean = selection.isActive(selectable(state))

    fun start() = selection.start()

    fun toggle(fileId: FilesItemId) = selection.toggle(fileId.value)

    fun selectAll(state: DownloadsState) = selection.selectAll(selectable(state))

    fun end() = selection.end()

    private fun selectable(state: DownloadsState): Set<Long> =
        state.entries.filter { it.fileId !in state.removing }.mapTo(linkedSetOf()) { it.fileId.value }

    companion object {
        val Saver: Saver<DownloadsSelection, Any> = Saver(
            save = { with(MobileSelection.Saver) { save(it.selection) } },
            restore = { saved -> MobileSelection.Saver.restore(saved)?.let(::DownloadsSelection) },
        )
    }
}

@Composable
internal fun rememberDownloadsSelection(): DownloadsSelection =
    rememberSaveable(saver = DownloadsSelection.Saver) { DownloadsSelection() }

/**
 * The Downloads tab's top: the tab title with its storage summary and an overflow holding Select
 * and the concurrency limit, or, in selection, a bar with close, the count and select all.
 */
@Composable
internal fun MobileDownloadsTopBar(
    title: String,
    summary: AnnotatedString?,
    state: DownloadsState,
    selection: DownloadsSelection,
    onEvent: (DownloadsEvent) -> Boolean,
    modifier: Modifier = Modifier,
    navigationActions: @Composable RowScope.() -> Unit = {},
) {
    var choosingConcurrency by rememberSaveable { mutableStateOf(false) }
    if (selection.isActive(state)) {
        MobileSelectionBar(
            count = selection.selected(state).size,
            onSelectAll = { selection.selectAll(state) },
            onClear = selection::end,
            modifier = modifier.testTag(MOBILE_DOWNLOADS_SELECTION_BAR_TAG),
        )
    } else {
        MobileTabTitle(title = title, summary = summary, hasActions = true, modifier = modifier) {
            navigationActions()
            DownloadsOverflow(
                canSelect = state.entries.any { it.fileId !in state.removing },
                concurrency = state.concurrency,
                onSelect = selection::start,
                onChooseConcurrency = { choosingConcurrency = true },
            )
        }
    }
    if (choosingConcurrency) {
        ConcurrencyDialog(
            current = state.concurrency,
            onChoose = { limit ->
                choosingConcurrency = false
                onEvent(DownloadsEvent.SetConcurrency(limit))
            },
            onDismiss = { choosingConcurrency = false },
        )
    }
}

@Composable
private fun DownloadsOverflow(
    canSelect: Boolean,
    concurrency: Int,
    onSelect: () -> Unit,
    onChooseConcurrency: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(MOBILE_DOWNLOADS_OVERFLOW_TAG)) {
            Icon(
                painterResource(R.drawable.ic_ph_dots_three_vertical),
                contentDescription = stringResource(R.string.mobile_action_more_options),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_downloads_select)) },
                onClick = {
                    open = false
                    onSelect()
                },
                enabled = canSelect,
                modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECT_TAG),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_downloads_concurrency)) },
                trailingIcon = {
                    Text(concurrency.toString(), style = MaterialTheme.typography.bodyMedium.putioMono())
                },
                onClick = {
                    open = false
                    onChooseConcurrency()
                },
                modifier = Modifier.testTag(MOBILE_DOWNLOADS_CONCURRENCY_TAG),
            )
        }
    }
}

/**
 * The selection's floating toolbar: Pause and Resume each show when a selected row allows them
 * and act on those rows; Remove asks before it removes every selected local copy.
 */
@Composable
internal fun DownloadsSelectionToolbar(
    state: DownloadsState,
    selected: Set<FilesItemId>,
    onEvent: (DownloadsEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val rows = selected.mapNotNull(state::entry)
    val pausable = rows.filter { it.canPause }.mapTo(mutableSetOf()) { it.fileId }
    val resumable = rows.filter { it.isPausedByUser }.mapTo(mutableSetOf()) { it.fileId }
    MobileSelectionToolbar(modifier) {
        if (pausable.isNotEmpty()) {
            MobileToolbarTile(
                icon = R.drawable.ic_ph_pause,
                label = stringResource(R.string.mobile_downloads_pause),
                onClick = { onEvent(DownloadsEvent.Pause(pausable)) },
                modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECTION_PAUSE_TAG),
            )
        }
        if (resumable.isNotEmpty()) {
            MobileToolbarTile(
                icon = R.drawable.ic_ph_play,
                label = stringResource(R.string.mobile_downloads_resume),
                onClick = { onEvent(DownloadsEvent.Resume(resumable)) },
                modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECTION_RESUME_TAG),
            )
        }
        MobileToolbarTile(
            icon = R.drawable.ic_ph_device_mobile_slash,
            label = stringResource(R.string.mobile_downloads_remove_short),
            description = stringResource(R.string.mobile_downloads_remove),
            color = PutioDesignTokens.redTextSecondary,
            enabled = selected.isNotEmpty(),
            onClick = { onEvent(DownloadsEvent.RequestRemoval(selected)) },
            modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG),
        )
    }
}

@Composable
private fun ConcurrencyDialog(current: Int, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mobile_downloads_concurrency)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.mobile_downloads_concurrency_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (limit in DOWNLOAD_CONCURRENCY_CHOICES) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = limit == current, role = Role.RadioButton) { onChoose(limit) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = limit == current, onClick = null)
                        Spacer(Modifier.size(16.dp))
                        Text(stringResource(R.string.mobile_downloads_concurrency_value, limit))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, colors = neutralTextButtonColors()) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}

