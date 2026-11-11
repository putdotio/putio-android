package io.putdotio.android.tv.search

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.FilesFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchFilter
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvDetailSkeleton
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvListDetail
import io.putdotio.android.tv.TvListSkeleton
import io.putdotio.android.tv.TvAutoPaging
import io.putdotio.android.tv.TvListPaging
import io.putdotio.android.tv.TvLoadFailedState
import io.putdotio.android.tv.TvNoticeDialog
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.TvPickedRow
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.paneSection
import io.putdotio.android.tv.rememberTvPaneHasFocus
import io.putdotio.android.tv.files.FILES_SHEET_SCRIM
import io.putdotio.android.tv.files.TvFilesEdits
import io.putdotio.android.tv.files.TvItemEditLayer
import io.putdotio.android.tv.files.TvItemEditTarget
import io.putdotio.android.tv.TvTrashUndoOffer
import io.putdotio.android.tv.TvTrashUndoOffered
import io.putdotio.android.tv.TvUndoSnackbar
import io.putdotio.android.tv.TvUndoSnackbarHost
import io.putdotio.android.tv.files.TvFilesAction
import io.putdotio.android.tv.files.TvFilesActionHandlers
import io.putdotio.android.tv.files.TvFilesActionsSheet
import io.putdotio.android.tv.files.TvFilesDetail
import io.putdotio.android.tv.files.TvFilesDetailActions
import io.putdotio.android.tv.files.TvFilesDetailEnabled
import io.putdotio.android.tv.files.TvFilesDetailFocus
import io.putdotio.android.tv.files.TvFilesDetailSources
import io.putdotio.android.tv.files.TvFileViewerLayer
import io.putdotio.android.tv.files.TvFileViewerState
import io.putdotio.android.tv.files.TvViewerUrl
import io.putdotio.android.tv.files.rememberBusy
import io.putdotio.android.tv.files.secondary
import io.putdotio.android.tv.files.run
import io.putdotio.android.tv.files.tvMessageText

internal class TvSearchActions(
    val onQueryChanged: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onResult: (FilesItem) -> Unit,
    val onNextPage: () -> Unit,
    val onRetry: () -> Unit,
    val recent: TvRecentSearchActions,
)

internal class TvSearchResultActions(
    val onFilter: (SearchFilter) -> Unit,
    val onPlay: (FilesItem, TvPlayStart) -> Unit,
    val itemActions: TvSearchItemActions,
)

internal class TvRecentSearchActions(
    val onSearch: (SearchTerm) -> Unit,
    val onEdit: (RecentSearchEdit) -> Unit,
    val onRetry: () -> Unit,
)

internal class TvSearchItemActions(
    val actionsOf: (FilesItem) -> List<TvFilesAction>,
    val handlers: TvFilesActionHandlers,
    val edits: TvFilesEdits? = null,
    val trashEnabled: Boolean? = null,
    val trashUndo: TvTrashUndoOffer? = null,
    val onAuthenticationRequired: suspend () -> Unit = {},
)

internal class TvSearchDetail(
    val folderNames: Map<FilesItemId, String>,
    val sources: TvFilesDetailSources,
    val resumePlayback: Boolean,
    /** Opens images, PDFs and text in the viewer instead of revealing them in Files. */
    val viewerUrl: TvViewerUrl? = null,
)

internal class TvSearchDialogNotice(
    val title: String,
    val message: String?,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvSearchScreen(
    state: SearchState,
    actions: TvSearchActions,
    results: TvSearchResultActions,
    detail: TvSearchDetail,
    modifier: Modifier = Modifier,
    notice: FilesFailure? = null,
    sessionKey: Any? = null,
    pickedRow: TvPickedRow = remember(sessionKey) { TvPickedRow() },
    dialogNotice: TvSearchDialogNotice? = null,
) = key(sessionKey) {
    var restoreRowId by remember { mutableStateOf(pickedRow.id) }
    val undoSnackbar = remember { TvUndoSnackbar(FocusRequester()) }
    val resultsShown = state.content is SearchContent.Ready
    LaunchedEffect(resultsShown) { if (!resultsShown) restoreRowId = null }
    val fieldFocus = remember { FocusRequester() }
    val entryTarget = remember { mutableStateOf(fieldFocus) }
    val paneHasFocus = rememberTvPaneHasFocus()
    val resultsFocus = remember { FocusRequester() }
    val lastSection = rememberSaveable { mutableStateOf<Long?>(null) }
    val owner = remember {
        TvPaneFocusOwner(
            entryTarget,
            paneHasFocus,
            lastSection,
            onEnter = pickedRow.keptOnlyBy(resultsFocus),
            home = { fieldFocus },
        )
    }
    val textState = rememberTextFieldState(state.query)
    val rewrite = remember { mutableStateOf<String?>(null) }
    val filtersFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var settingsFrom by remember { mutableStateOf<FocusRequester?>(null) }
    val historyEnabled = state.recentSearchesEnabled
    val openSettings = historyEnabled?.let { { from: FocusRequester -> settingsFrom = from } }
    val overlayOpen = rememberUpdatedState(settingsFrom != null || dialogNotice != null)
    owner.RefocusAfterLeave(blocked = overlayOpen)
    Box(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { if (it.hasFocus) paneHasFocus.value = true }
            .focusProperties {
                enter = { entryTarget.value }
                exit = {
                    paneHasFocus.value = false
                    FocusRequester.Default
                }
            }
            .focusGroup(),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        TvSearchField(
            state = textState,
            rewrite = rewrite,
            query = state.query,
            actions = TvSearchFieldActions(
                onQueryChanged = actions.onQueryChanged,
                onSubmit = actions.onSubmit,
                onMenu = openSettings?.let { open -> { open(fieldFocus) } },
                onCleared = { fieldFocus.requestFocus() },
            ),
            focus = fieldFocus,
            modifier = Modifier
                .padding(top = PANE_TOP)
                .paneSection(owner, fieldFocus),
        )
        TvSearchFilters(
            selected = state.filter,
            onSelect = results.onFilter,
            onSettings = openSettings?.let { open -> { open(settingsFocus) } },
            owner = owner,
            focus = filtersFocus,
            settingsFocus = settingsFocus,
            modifier = Modifier.padding(top = FILTERS_TOP),
        )
        if (notice != null) {
            TvSearchNotice(notice, onRetry = actions.recent.onRetry, owner = owner)
        }
        TvSearchContent(
            state = state,
            actions = actions,
            results = results,
            owner = owner,
            fieldFocus = fieldFocus,
            resultsFocus = resultsFocus,
            restoreRowId = restoreRowId,
            pickedRow = pickedRow,
            detail = detail,
            undoSnackbar = undoSnackbar,
            onRecentSearch = { term ->
                textState.rewriteTo(term.value, rewrite)
                actions.recent.onSearch(term)
            },
        )
    }
    TvTrashUndoOffered(results.itemActions.trashUndo, undoSnackbar.state)
    TvUndoSnackbarHost(undoSnackbar.state, undoSnackbar.undoFocus, entryTarget)
    }
    val from = settingsFrom
    if (from != null && historyEnabled != null) {
        TvSearchSettingsSheet(
            enabled = historyEnabled,
            canClear = state.recentTerms.isNotEmpty(),
            onEdit = actions.recent.onEdit,
            onDismiss = { settingsFrom = null },
            returnFocus = from,
        )
    } else if (from != null) {
        LaunchedEffect(Unit) { settingsFrom = null }
    }
    if (dialogNotice != null) {
        TvNoticeDialog(
            title = dialogNotice.title,
            message = dialogNotice.message,
            onDismiss = dialogNotice.onDismiss,
            okStyle = TvButtonStyle.Filled,
            balanceMessage = true,
            okHugsLabel = true,
            dimAmount = FILES_SHEET_SCRIM.alpha,
        )
    }
    LaunchedEffect(owner) { owner.restoreLastSection() }
}

@Composable
private fun ColumnScope.TvSearchContent(
    state: SearchState,
    actions: TvSearchActions,
    results: TvSearchResultActions,
    owner: TvPaneFocusOwner,
    fieldFocus: FocusRequester,
    resultsFocus: FocusRequester,
    restoreRowId: Long?,
    pickedRow: TvPickedRow,
    detail: TvSearchDetail,
    undoSnackbar: TvUndoSnackbar,
    onRecentSearch: (SearchTerm) -> Unit,
) {
    val hadRecent = remember { mutableStateOf(false) }
    val showsRecent = state.content == SearchContent.Idle && state.recentTerms.isNotEmpty()
    LaunchedEffect(showsRecent) {
        if (!showsRecent && hadRecent.value && owner.owns(fieldFocus)) {
            withFrameNanos {}
            if (owner.owns(fieldFocus)) fieldFocus.requestFocus()
        }
        hadRecent.value = showsRecent
    }
    when (val content = state.content) {
        SearchContent.Idle ->
            if (showsRecent) {
                TvRecentSearches(
                    terms = state.recentTerms,
                    onSearch = onRecentSearch,
                    onRemove = { actions.recent.onEdit(RecentSearchEdit.Remove(it)) },
                    owner = owner,
                    modifier = Modifier.padding(top = CONTENT_TOP),
                )
            } else {
                Text(
                    text = stringResource(R.string.tv_search_start),
                    style = PutioTypeScale.tvLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = CONTENT_TOP),
                )
            }
        is SearchContent.Debouncing, is SearchContent.Loading ->
            TvListDetail(
                list = { TvListSkeleton(Modifier.padding(top = TV_SEARCH_LABEL_HEIGHT + TV_SEARCH_LABEL_GAP)) },
                detail = { TvDetailSkeleton() },
                modifier = Modifier
                    .weight(1f)
                    .padding(top = CONTENT_TOP),
            )
        is SearchContent.Empty ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                TvEmptyState(
                    glyph = PutioEmptyGlyph.NoResults,
                    headline = stringResource(R.string.tv_search_no_results_title),
                    message = stringResource(R.string.tv_search_no_results_message, content.term.value),
                    modifier = Modifier.padding(top = EMPTY_TOP, bottom = EMPTY_BOTTOM),
                    glyphModifier = Modifier.offset(y = -SHRUG_LIFT),
                )
                TvAutoPaging(page = content.paging as? SearchPaging.Available, onLoadMore = actions.onNextPage)
                TvSearchPaging(
                    paging = content.paging,
                    onRetry = {
                        owner.focusHome()
                        actions.onRetry()
                    },
                    owner = owner,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        is SearchContent.Ready ->
            TvSearchReady(
                content = content,
                actions = actions,
                results = results,
                owner = owner,
                resultsFocus = resultsFocus,
                restoreRowId = restoreRowId,
                pickedRow = pickedRow,
                detail = detail,
                undoSnackbar = undoSnackbar,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = CONTENT_TOP),
            )
        is SearchContent.Failed -> {
            val retryFocus = remember { FocusRequester() }
            TvLoadFailedState(
                failure = content.failure,
                headline = stringResource(R.string.tv_search_error_title),
                message = content.failure.tvMessageText(),
                onRetry = {
                    fieldFocus.requestFocus()
                    actions.onRetry()
                },
                modifier = Modifier
                    .paneSection(owner, retryFocus)
                    .weight(1f)
                    .padding(top = EMPTY_TOP, bottom = EMPTY_BOTTOM),
                actionFocus = retryFocus,
                claimFocus = false,
            )
        }
    }
}

@Composable
private fun TvSearchReady(
    content: SearchContent.Ready,
    actions: TvSearchActions,
    results: TvSearchResultActions,
    owner: TvPaneFocusOwner,
    resultsFocus: FocusRequester,
    restoreRowId: Long?,
    pickedRow: TvPickedRow,
    detail: TvSearchDetail,
    undoSnackbar: TvUndoSnackbar,
    modifier: Modifier = Modifier,
) {
    val groups = remember(content.items) { TvSearchGroups(content.items) }
    var detailId by remember { mutableStateOf(restoreRowId) }
    val detailItem = groups.ordered.firstOrNull { it.id.value == detailId } ?: groups.ordered.first()
    val detailFocus = remember { TvFilesDetailFocus() }
    val detailRow = remember { FocusRequester() }
    val listing = remember(content.items) { Any() }
    var actionsFor by remember { mutableStateOf<Long?>(null) }
    val itemActions = results.itemActions
    val edits = itemActions.edits
    val actionsOf = { item: FilesItem ->
        itemActions.actionsOf(item).filter { action ->
            edits != null ||
                (action !is TvFilesAction.Delete && action != TvFilesAction.Rename && action != TvFilesAction.Move)
        }
    }
    val edit = remember { TvItemEditTarget() }
    val editing = edits.rememberBusy()
    val runAction = { item: FilesItem, action: TvFilesAction ->
        when (action) {
            TvFilesAction.Rename, TvFilesAction.Move, is TvFilesAction.Delete -> edit.press(action, item)
            else -> itemActions.handlers.run(action, item)
        }
    }
    val openActions = { item: FilesItem -> if (actionsOf(item).isNotEmpty()) actionsFor = item.id.value }
    val viewer = remember { TvFileViewerState() }
    val openResult = { item: FilesItem, returnFocus: FocusRequester ->
        viewer.openOr(item, detail.viewerUrl, { returnFocus }, actions.onResult)
    }
    val rows = TvSearchRows(
        folderName = folderNameOf(detail.folderNames, stringResource(R.string.tv_files_title)),
        onResult = { openResult(it, detailRow) },
        onActions = openActions,
        onFocused = {
            detailId = it.id.value
            pickedRow.pick(it.id.value)
            detailFocus.onListFocusChanged(true)
        },
        detail = TvSearchRowDetail(detailItem.id.value, detailRow),
    )
    TvListDetail(
        modifier = modifier,
        detailFocus = detailFocus.primary,
        listFocus = resultsFocus,
        onDetailFocusChanged = detailFocus::onDetailFocusChanged,
        list = {
            TvSearchResultList(
                groups = groups,
                paging = content.paging,
                restoreRowId = restoreRowId,
                rows = rows,
                onNextPage = actions.onNextPage,
                onRetry = actions.onRetry,
                owner = owner,
                listFocus = resultsFocus,
                modifier = Modifier.weight(1f),
            )
        },
        detail = {
            TvFilesDetail(
                item = detailItem,
                focus = detailFocus,
                resumePlayback = detail.resumePlayback,
                sources = detail.sources,
                listing = listing,
                actions = TvFilesDetailActions(
                    onOpenFolder = actions.onResult,
                    onPlay = results.onPlay,
                    onUnsupported = { openResult(it, detailFocus.primary) },
                    secondary = actionsOf(detailItem).secondary(),
                    onSecondary = runAction,
                    enabled = TvFilesDetailEnabled(delete = !editing, edits = !editing),
                ),
                place = rows.place(detailItem),
            )
        },
    )
    TvFileViewerLayer(viewer, groups.ordered, detail.viewerUrl, siblings = false)
    if (edits != null) {
        TvItemEditLayer(
            target = edit,
            edits = edits,
            trashEnabled = itemActions.trashEnabled,
            snackbar = undoSnackbar.state,
            onAuthenticationRequired = itemActions.onAuthenticationRequired,
        )
        TvSearchEditFocus(detailId, groups, detailRow, detailFocus, owner, resultsFocus) { detailId = it }
    }
    val open = actionsFor
    val openItem = open?.let { id -> groups.ordered.firstOrNull { it.id.value == id } }
    if (open != null && openItem != null) {
        TvFilesActionsSheet(
            item = openItem,
            actions = actionsOf(openItem),
            onAction = { action ->
                actionsFor = null
                runAction(openItem, action)
            },
            onDismiss = { actionsFor = null },
            returnFocus = detailRow,
        )
    } else if (open != null) {
        LaunchedEffect(Unit) { actionsFor = null }
    }
}

@Composable
private fun TvSearchEditFocus(
    detailId: Long?,
    groups: TvSearchGroups,
    detailRow: FocusRequester,
    detailFocus: TvFilesDetailFocus,
    owner: TvPaneFocusOwner,
    resultsFocus: FocusRequester,
    onDetailId: (Long) -> Unit,
) {
    val ids = remember(groups) { groups.ordered.map { it.id.value } }
    val lastIndex = remember { mutableIntStateOf(0) }
    val landings = remember { mutableIntStateOf(0) }
    SideEffect {
        val index = detailId?.let(ids::indexOf) ?: -1
        when {
            index >= 0 -> lastIndex.intValue = index
            detailId != null -> {
                onDetailId(ids.getOrElse(lastIndex.intValue) { ids.last() })
                landings.intValue++
            }
        }
    }
    LaunchedEffect(landings.intValue) {
        if (landings.intValue > 0 && (detailFocus.owns.value || owner.owns(resultsFocus))) {
            withFrameNanos {}
            runCatching { detailRow.requestFocus() }
        }
    }
}

@Composable
private fun TvSearchNotice(
    failure: FilesFailure,
    onRetry: () -> Unit,
    owner: TvPaneFocusOwner,
) {
    val retryFocus = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CONTENT_TOP),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val message = if (failure == FilesFailure.NavigationBlocked) {
            stringResource(R.string.tv_error_navigation_blocked)
        } else {
            stringResource(R.string.tv_search_recent_error)
        }
        Text(message, style = PutioTypeScale.tvMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failure != FilesFailure.NavigationBlocked) {
            TvButton(
                onClick = {
                    owner.focusHome()
                    onRetry()
                },
                modifier = Modifier.paneSection(owner, retryFocus).focusRequester(retryFocus),
            ) {
                Text(stringResource(R.string.tv_files_retry))
            }
        }
    }
}

@Composable
internal fun TvSearchPaging(
    paging: SearchPaging,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    owner: TvPaneFocusOwner? = null,
    buttonModifier: Modifier = Modifier,
) {
    val pagingFocus = remember { FocusRequester() }
    if (paging == SearchPaging.Complete) return
    TvListPaging(
        failed = paging is SearchPaging.Failed,
        loadingMore = stringResource(R.string.tv_search_loading_more),
        error = stringResource(R.string.tv_search_paging_error),
        retry = stringResource(R.string.tv_files_retry),
        onRetry = onRetry,
        modifier = modifier,
        buttonModifier = buttonModifier
            .then(if (owner != null) Modifier.paneSection(owner, pagingFocus) else Modifier)
            .focusRequester(pagingFocus),
        errorStyle = PutioTypeScale.tvMeta,
    )
}

private val PANE_TOP = 4.dp
private val FILTERS_TOP = 10.dp
private val CONTENT_TOP = 20.dp
private val SHRUG_LIFT = 2.dp
private val EMPTY_TOP = 16.dp
private val EMPTY_BOTTOM = 3.dp
