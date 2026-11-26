package io.putdotio.android.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.putdotio.android.MobileSelection
import io.putdotio.android.MobileSelectionToolbar
import io.putdotio.android.MobileSelectionToolbarClearance
import io.putdotio.android.MobileSelectionToolbarOffset
import io.putdotio.android.MobileToolbarTile
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsState
import kotlinx.coroutines.flow.distinctUntilChanged

internal const val MOBILE_FILES_SELECTION_BAR_TAG = "mobile-files-selection-bar"
internal const val MOBILE_FILES_SELECTION_TOOLBAR_TAG = "mobile-files-selection-toolbar"
internal const val MOBILE_FILES_SELECTION_MOVE_TAG = "mobile-files-selection-move"
internal const val MOBILE_FILES_SELECTION_DOWNLOAD_TAG = "mobile-files-selection-download"
internal const val MOBILE_FILES_SELECTION_WATCHED_TAG = "mobile-files-selection-watched"
internal const val MOBILE_FILES_SELECTION_DELETE_TAG = "mobile-files-selection-delete"

@Stable
internal class FilesSelection(
    private val selection: MobileSelection = MobileSelection(),
    scope: FilesSelectionScope? = null,
) {
    private var scope by mutableStateOf(scope)

    fun isActive(state: FilesBrowserState): Boolean =
        scope == state.selectionScope() && selection.isActive(state.selectableIds())

    fun selected(state: FilesBrowserState): List<FilesItem> {
        if (scope != state.selectionScope()) return emptyList()
        val ids = selection.selected(state.selectableIds())
        return state.current.content.items().filter { it.id.value in ids }
    }

    fun start(state: FilesBrowserState, item: FilesItem) {
        val current = state.selectionScope()
        if (scope != current) {
            selection.end()
            scope = current
        }
        if (item.id.value in state.selectableIds() && item.id.value !in selection.selected(state.selectableIds())) {
            selection.toggle(item.id.value)
        }
        selection.start()
    }

    fun toggle(state: FilesBrowserState, item: FilesItem) {
        if (scope == state.selectionScope()) selection.toggle(item.id.value)
    }

    fun selectAll(state: FilesBrowserState) {
        if (scope == state.selectionScope()) selection.selectAll(state.selectableIds())
    }

    fun end() {
        selection.end()
        scope = null
    }

    fun reconcile(state: FilesBrowserState) {
        val current = scope ?: return
        val selectable = state.selectableIds()
        val listed = state.current.content is FilesContent.Ready || state.current.content is FilesContent.Empty
        when {
            current != state.selectionScope() -> end()
            state.current.content is FilesContent.Loading -> end()
            listed && selectable.isEmpty() -> end()
            listed -> selection.retain(selectable)
        }
    }

    companion object {
        val Saver: Saver<FilesSelection, Any> = Saver(
            save = { selection ->
                val scope = selection.scope
                listOf(
                    with(MobileSelection.Saver) { save(selection.selection) },
                    scope?.depth ?: -1,
                    scope?.folderId ?: -1L,
                )
            },
            restore = { saved ->
                val values = saved as List<*>
                val depth = values[1] as Int
                FilesSelection(
                    MobileSelection.Saver.restore(checkNotNull(values[0])) ?: MobileSelection(),
                    if (depth < 0) null else FilesSelectionScope(depth, values[2] as Long),
                )
            },
        )
    }
}

internal data class FilesSelectionScope(val depth: Int, val folderId: Long)

private fun FilesBrowserState.selectionScope() = FilesSelectionScope(stack.size, current.folder.id.value)

private fun FilesBrowserState.selectableIds(): Set<Long> =
    current.content.items().filter { it.id.value > 0L }.mapTo(linkedSetOf()) { it.id.value }

@Composable
internal fun rememberFilesSelection(key: Any?, states: State<FilesBrowserState>): FilesSelection {
    val selection = rememberSaveable(key, saver = FilesSelection.Saver) { FilesSelection() }
    LaunchedEffect(selection, states) {
        snapshotFlow { states.value }
            .distinctUntilChanged { old, new ->
                old.selectionScope() == new.selectionScope() && old.current.content == new.current.content
            }
            .collect(selection::reconcile)
    }
    return selection
}

internal data class FilesSelectionActions(
    val move: Boolean,
    val download: Boolean,
    val watched: Boolean?,
    val delete: Boolean,
) {
    val any: Boolean get() = move || download || watched != null || delete
}

internal fun List<FilesItem>.selectionActions(
    canMove: Boolean,
    canDownload: Boolean,
    canSetWatched: Boolean,
): FilesSelectionActions {
    if (isEmpty()) return FilesSelectionActions(move = false, download = false, watched = null, delete = false)
    val owned = all { it.acceptsOwnerActions && it.id.value > 0L }
    return FilesSelectionActions(
        move = canMove && owned,
        download = canDownload && all { it.isPlayable && it.id.value > 0L },
        watched = if (canSetWatched && owned) selectionWatchedChoice() else null,
        delete = owned,
    )
}

private fun List<FilesItem>.selectionWatchedChoice(): Boolean? {
    val choices = map { it.watchedChoice() }
    return when {
        choices.any { it == null } -> null
        choices.all { it == false } -> false
        all { it.playback?.durationSeconds != null } -> true
        else -> null
    }
}

internal class MobileFilesSelectionHandlers(
    val onMove: ((List<FilesItem>) -> Unit)?,
    val onDownload: ((List<FilesItem>) -> Unit)?,
    val onSetWatched: ((List<FilesItem>, Boolean) -> Unit)?,
    val onDelete: (List<FilesItem>, FilesDeleteMode) -> Unit,
)

@Composable
internal fun MobileFilesSelectionToolbar(
    items: List<FilesItem>,
    downloads: DownloadsState,
    operation: FilesFolderOperation,
    confirmedTrashEnabled: Boolean?,
    handlers: MobileFilesSelectionHandlers,
    modifier: Modifier = Modifier,
) {
    val actions = items.selectionActions(
        canMove = handlers.onMove != null,
        canDownload = handlers.onDownload != null,
        canSetWatched = handlers.onSetWatched != null,
    )
    val label = pluralStringResource(R.plurals.mobile_files_selection_actions, items.size, items.size)
    val notHere = items.filter { (downloads.entry(it.id)?.status).downloadable }
    MobileSelectionToolbar(
        modifier.testTag(MOBILE_FILES_SELECTION_TOOLBAR_TAG).semantics { contentDescription = label },
    ) {
        val canStart = operation.canStartOperation
        if (actions.move) MoveTile(enabled = canStart) { handlers.onMove?.invoke(items) }
        if (actions.download) DownloadTile { handlers.onDownload?.invoke(notHere) }
        actions.watched?.let { watched -> WatchedTile(watched) { handlers.onSetWatched?.invoke(items, watched) } }
        if (actions.delete) {
            DeleteAction(items, operation.canStartOperation, confirmedTrashEnabled, handlers.onDelete)
        }
    }
}

@Composable
private fun DeleteAction(
    items: List<FilesItem>,
    canStart: Boolean,
    confirmedTrashEnabled: Boolean?,
    onDelete: (List<FilesItem>, FilesDeleteMode) -> Unit,
) {
    var confirmingPermanentDelete by rememberSaveable { mutableStateOf(false) }
    var trashTapped by remember { mutableStateOf(false) }
    val currentTrashEnabled by rememberUpdatedState(confirmedTrashEnabled)
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(confirmedTrashEnabled) {
        if (confirmedTrashEnabled != false) confirmingPermanentDelete = false
    }
    if (trashTapped) {
        SideEffect {
            trashTapped = false
            if (currentTrashEnabled == true) onDelete(currentItems, FilesDeleteMode.TRASH)
        }
    }
    DeleteTile(permanent = confirmedTrashEnabled == false, enabled = canStart && confirmedTrashEnabled != null) {
        when (currentTrashEnabled) {
            true -> trashTapped = true
            false -> confirmingPermanentDelete = true
            null -> Unit
        }
    }
    if (confirmingPermanentDelete && confirmedTrashEnabled == false) {
        MobileFilesDeleteConfirmation(
            enabled = canStart,
            count = items.size,
            onDismiss = { confirmingPermanentDelete = false },
            onConfirm = {
                confirmingPermanentDelete = false
                if (currentTrashEnabled == false) onDelete(currentItems, FilesDeleteMode.PERMANENT)
            },
        )
    }
}

@Composable
private fun MoveTile(enabled: Boolean, onClick: () -> Unit) {
    MobileToolbarTile(
        icon = R.drawable.ic_ph_folder_simple_dashed,
        label = stringResource(R.string.mobile_files_move),
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.testTag(MOBILE_FILES_SELECTION_MOVE_TAG),
    )
}

@Composable
private fun DownloadTile(onClick: () -> Unit) {
    MobileToolbarTile(
        icon = R.drawable.ic_ph_arrow_circle_down,
        label = stringResource(R.string.mobile_files_download_action),
        onClick = onClick,
        modifier = Modifier.testTag(MOBILE_FILES_SELECTION_DOWNLOAD_TAG),
    )
}

@Composable
private fun WatchedTile(watched: Boolean, onClick: () -> Unit) {
    MobileToolbarTile(
        icon = if (watched) R.drawable.ic_ph_eye else R.drawable.ic_ph_eye_slash,
        label = stringResource(if (watched) R.string.mobile_files_watched else R.string.mobile_files_unwatched),
        description = stringResource(
            if (watched) R.string.mobile_files_mark_watched else R.string.mobile_files_mark_unwatched,
        ),
        onClick = onClick,
        modifier = Modifier.testTag(MOBILE_FILES_SELECTION_WATCHED_TAG),
    )
}

@Composable
private fun DeleteTile(permanent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    MobileToolbarTile(
        icon = R.drawable.ic_ph_trash,
        label = stringResource(if (permanent) R.string.mobile_files_delete else R.string.mobile_files_trash_short),
        description = stringResource(
            if (permanent) R.string.mobile_files_delete_permanently else R.string.mobile_files_trash,
        ),
        color = PutioDesignTokens.redTextSecondary,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.testTag(MOBILE_FILES_SELECTION_DELETE_TAG),
    )
}

internal class MobileFilesSelectionSources(
    val onMove: ((List<FilesItem>) -> Unit)?,
    val onDownload: ((FilesItem) -> Unit)?,
    val onSetWatched: ((List<FilesItem>, Boolean) -> Unit)?,
    val onEvent: (FilesBrowserEvent) -> Unit,
)

@Composable
internal fun MobileFilesSelectionToolbarSlot(
    state: FilesBrowserState,
    selection: FilesSelection?,
    downloads: DownloadsState,
    confirmedTrashEnabled: Boolean?,
    actions: MobileFilesSelectionSources,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentState by rememberUpdatedState(state)
    val items = selection?.selected(state).orEmpty()
    val offered = items.selectionActions(
        canMove = actions.onMove != null,
        canDownload = actions.onDownload != null,
        canSetWatched = actions.onSetWatched != null,
    ).any
    if (selection == null || !offered) {
        SideEffect { onHeightChanged(0) }
        return
    }
    val sendOnce = { send: () -> Unit ->
        if (selection.isActive(currentState)) {
            send()
            selection.end()
        }
    }
    val handlers = MobileFilesSelectionHandlers(
        onMove = actions.onMove,
        onDownload = actions.onDownload?.let { download ->
            { chosen -> sendOnce { chosen.forEach(download) } }
        },
        onSetWatched = actions.onSetWatched?.let { setWatched ->
            { chosen, watched -> sendOnce { setWatched(chosen, watched) } }
        },
        onDelete = { chosen, mode ->
            if (currentState.current.operation.canStartOperation) {
                val folderId = currentState.current.folder.id
                sendOnce { actions.onEvent(FilesBrowserEvent.Delete(folderId, chosen.map { it.id }, mode)) }
            }
        },
    )
    MobileFilesSelectionToolbar(
        items = items,
        downloads = downloads,
        operation = state.current.operation,
        confirmedTrashEnabled = confirmedTrashEnabled,
        handlers = handlers,
        modifier = modifier
            .padding(bottom = MobileSelectionToolbarOffset)
            .onSizeChanged { onHeightChanged(it.height) },
    )
}

internal class MobileFilesListSelection(
    val checked: Set<FilesItemId>,
    val onToggle: (FilesItem) -> Unit,
    val bottomClearance: Dp,
)

internal fun FilesSelection?.isActiveIn(state: FilesBrowserState): Boolean = this?.isActive(state) == true

@Composable
internal fun selectionToolbarClearance(heightPx: Int): Dp =
    with(LocalDensity.current) { heightPx.toDp() } + MobileSelectionToolbarClearance

internal fun FilesSelection?.listSelection(
    state: FilesBrowserState,
    bottomClearance: Dp,
): MobileFilesListSelection? {
    val selection = this?.takeIf { it.isActive(state) } ?: return null
    return MobileFilesListSelection(
        checked = selection.selected(state).mapTo(mutableSetOf()) { it.id },
        onToggle = { item -> selection.toggle(state, item) },
        bottomClearance = bottomClearance,
    )
}

internal fun MobileFilesItemActions.selectingIn(
    selection: FilesSelection?,
    state: () -> FilesBrowserState,
): MobileFilesItemActions = selection?.let { withSelect { item -> it.start(state(), item) } } ?: this

internal fun FilesSelection?.starter(state: () -> FilesBrowserState): ((FilesItem) -> Unit)? =
    this?.let { selection -> { item -> selection.start(state(), item) } }

private val DownloadStatus?.downloadable: Boolean
    get() = this == null || this is DownloadStatus.Failed || this == DownloadStatus.Missing
