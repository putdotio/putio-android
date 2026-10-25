package io.putdotio.android.downloads

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileTabTitle
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
internal class DownloadsSelection(selecting: Boolean = false, selected: List<Long> = emptyList()) {
    private var selecting by mutableStateOf(selecting)
    private var selectedIds by mutableStateOf(selected)

    fun selected(state: DownloadsState): Set<FilesItemId> {
        val selectable = selectable(state)
        return selectedIds.map(::FilesItemId).filterTo(mutableSetOf()) { it in selectable }
    }

    fun isActive(state: DownloadsState): Boolean =
        (selecting || selected(state).isNotEmpty()) && selectable(state).isNotEmpty()

    fun start() {
        selecting = true
    }

    fun toggle(fileId: FilesItemId) {
        selecting = true
        val id = fileId.value
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll(state: DownloadsState) {
        selecting = true
        selectedIds = selectable(state).map { it.value }
    }

    fun end() {
        selecting = false
        selectedIds = emptyList()
    }

    private fun selectable(state: DownloadsState): Set<FilesItemId> =
        state.entries.map { it.fileId }.filterTo(mutableSetOf()) { it !in state.removing }

    companion object {
        val Saver: Saver<DownloadsSelection, Any> = Saver(
            save = { listOf(it.selecting, it.selectedIds.toLongArray()) },
            restore = { saved ->
                val values = saved as List<*>
                DownloadsSelection(values[0] as Boolean, (values[1] as LongArray).toList())
            },
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
        DownloadsSelectionBar(
            count = selection.selected(state).size,
            onSelectAll = { selection.selectAll(state) },
            onClear = selection::end,
            modifier = modifier,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsSelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The status bar keeps the page colour; only the bar itself takes the selection tone.
    Box(modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
        TopAppBar(
            title = { Text(selectedCount(count)) },
            navigationIcon = {
                IconButton(onClick = onClear) {
                    Icon(
                        painterResource(R.drawable.ic_ph_x),
                        contentDescription = stringResource(R.string.mobile_downloads_selection_clear),
                    )
                }
            },
            actions = {
                IconButton(onClick = onSelectAll) {
                    Icon(
                        painterResource(R.drawable.ic_ph_checks),
                        contentDescription = stringResource(R.string.mobile_downloads_select_all),
                    )
                }
            },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECTION_BAR_TAG),
        )
    }
}

/** "3 selected", its count in mono. */
@Composable
private fun selectedCount(count: Int): AnnotatedString {
    val text = pluralStringResource(R.plurals.mobile_downloads_selected, count, count)
    val number = count.toString()
    val at = text.indexOf(number)
    val mono = MaterialTheme.typography.titleLarge.putioMono().toSpanStyle()
    return if (at < 0) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString {
            append(text.substring(0, at))
            withStyle(mono) { append(number) }
            append(text.substring(at + number.length))
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
    // Material 3's floating toolbar is still internal here; this is its tonal container, without a shadow.
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(TOOLBAR_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(TOOLBAR_PADDING),
            horizontalArrangement = Arrangement.spacedBy(TOOLBAR_GAP),
        ) {
            if (pausable.isNotEmpty()) {
                ToolbarTile(
                    icon = R.drawable.ic_ph_pause,
                    label = stringResource(R.string.mobile_downloads_pause),
                    onClick = { onEvent(DownloadsEvent.Pause(pausable)) },
                    modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECTION_PAUSE_TAG),
                )
            }
            if (resumable.isNotEmpty()) {
                ToolbarTile(
                    icon = R.drawable.ic_ph_play,
                    label = stringResource(R.string.mobile_downloads_resume),
                    onClick = { onEvent(DownloadsEvent.Resume(resumable)) },
                    modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECTION_RESUME_TAG),
                )
            }
            ToolbarTile(
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
}

/** An icon over a label-sm word, in a target of at least 64x52dp. */
@Composable
private fun ToolbarTile(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
    color: Color = LocalContentColor.current,
    enabled: Boolean = true,
) {
    val tint = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
    Column(
        modifier = modifier
            // At least 64x52dp; a large font grows the tile instead of clipping its word.
            .defaultMinSize(TILE_WIDTH, TILE_HEIGHT)
            .clip(RoundedCornerShape(TILE_CORNER))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(TILE_ICON))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
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

private const val DISABLED_ALPHA = 0.38f
private val TOOLBAR_PADDING = 6.dp
private val TOOLBAR_GAP = 4.dp
private val TOOLBAR_CORNER = 28.dp
private val TILE_WIDTH = 64.dp
private val TILE_HEIGHT = 52.dp
private val TILE_CORNER = 22.dp
private val TILE_ICON = 22.dp
