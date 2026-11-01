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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.putdotio.android.tv.TvLoadFailedState
import io.putdotio.android.tv.TvNoticeDialog
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.TvPickedRow
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.paneSection
import io.putdotio.android.tv.rememberTvPaneHasFocus
import io.putdotio.android.tv.files.FILES_SHEET_SCRIM
import io.putdotio.android.tv.files.TvFilesAction
import io.putdotio.android.tv.files.TvFilesActionHandlers
import io.putdotio.android.tv.files.TvFilesActionsSheet
import io.putdotio.android.tv.files.TvFilesDetail
import io.putdotio.android.tv.files.TvFilesDetailActions
import io.putdotio.android.tv.files.TvFilesDetailFocus
import io.putdotio.android.tv.files.TvFilesDetailSources
import io.putdotio.android.tv.files.resumePosition
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
)

internal class TvSearchDetail(
    val folderNames: Map<FilesItemId, String>,
    val sources: TvFilesDetailSources,
    val resumePlayback: Boolean,
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
    Column(
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
            onRecentSearch = { term ->
                textState.rewriteTo(term.value, rewrite)
                actions.recent.onSearch(term)
            },
        )
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
                detailTop = DETAIL_TOP,
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
                TvSearchPaging(
                    paging = content.paging,
                    onNextPage = actions.onNextPage,
                    onRetry = actions.onRetry,
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
    modifier: Modifier = Modifier,
) {
    val groups = remember(content.items) { TvSearchGroups(content.items) }
    var detailId by remember { mutableStateOf(restoreRowId) }
    val detailItem = groups.ordered.firstOrNull { it.id.value == detailId } ?: groups.ordered.first()
    val detailFocus = remember { TvFilesDetailFocus() }
    val detailRow = remember { FocusRequester() }
    val listing = remember(content.items) { Any() }
    var actionsFor by remember { mutableStateOf<Pair<Long, FocusRequester>?>(null) }
    val itemActions = results.itemActions
    val offersMore = { item: FilesItem -> itemActions.actionsOf(item).isNotEmpty() }
    val openActions = { item: FilesItem, from: FocusRequester ->
        if (offersMore(item)) actionsFor = item.id.value to from
    }
    val rows = TvSearchRows(
        folderName = folderNameOf(detail.folderNames, stringResource(R.string.tv_destination_files)),
        onResult = actions.onResult,
        onActions = { openActions(it, detailRow) },
        onFocused = {
            detailId = it.id.value
            pickedRow.pick(it.id.value)
            detailFocus.onListFocusChanged(true)
        },
        detailId = detailItem.id.value,
        detailRow = detailRow,
    )
    TvListDetail(
        modifier = modifier,
        detailFocus = detailFocus.primary,
        listFocus = resultsFocus,
        onDetailFocusChanged = detailFocus::onDetailFocusChanged,
        detailTop = DETAIL_TOP,
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
            val current by rememberUpdatedState(detailItem)
            TvFilesDetail(
                item = detailItem,
                detailHasFocus = detailFocus.hasFocus,
                primaryFocus = detailFocus.primary,
                resumePlayback = detail.resumePlayback,
                sources = detail.sources,
                listing = listing,
                actions = TvFilesDetailActions(
                    onOpenFolder = actions.onResult,
                    onPlay = results.onPlay,
                    onUnsupported = actions.onResult,
                    onMore = if (offersMore(detailItem)) {
                        { openActions(current, detailFocus.more) }
                    } else {
                        null
                    },
                ),
                moreFocus = detailFocus.more,
                place = rows.place(detailItem),
            )
        },
    )
    val open = actionsFor
    val openItem = open?.let { (id, _) -> groups.ordered.firstOrNull { it.id.value == id } }
    if (open != null && openItem != null) {
        val playFrom = if (openItem.resumePosition(detail.resumePlayback) != null) {
            TvPlayStart.Resume
        } else {
            TvPlayStart.Ask
        }
        TvFilesActionsSheet(
            item = openItem,
            actions = itemActions.actionsOf(openItem).filterNot { it is TvFilesAction.Delete },
            onAction = { action ->
                actionsFor = null
                itemActions.handlers.run(action, openItem, playFrom)
            },
            onDismiss = { actionsFor = null },
            returnFocus = open.second,
        )
    } else if (open != null) {
        LaunchedEffect(Unit) { actionsFor = null }
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
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    owner: TvPaneFocusOwner? = null,
    buttonModifier: Modifier = Modifier,
) {
    val pagingFocus = remember { FocusRequester() }
    val label = when (paging) {
        is SearchPaging.Available -> R.string.tv_search_load_more
        is SearchPaging.Loading -> R.string.tv_search_loading_more
        is SearchPaging.Failed -> R.string.tv_files_retry
        SearchPaging.Complete -> null
    } ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paging is SearchPaging.Failed) {
            Text(
                stringResource(R.string.tv_search_paging_error),
                style = PutioTypeScale.tvMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TvButton(
            onClick = {
                when (paging) {
                    is SearchPaging.Available -> onNextPage()
                    is SearchPaging.Failed -> onRetry()
                    is SearchPaging.Loading, SearchPaging.Complete -> Unit
                }
            },
            modifier = buttonModifier
                .then(if (owner != null) Modifier.paneSection(owner, pagingFocus) else Modifier)
                .focusRequester(pagingFocus),
        ) {
            Text(stringResource(label))
        }
    }
}

private val PANE_TOP = 4.dp
private val FILTERS_TOP = 10.dp
private val CONTENT_TOP = 20.dp
private val DETAIL_TOP = TV_SEARCH_LABEL_HEIGHT + TV_SEARCH_LABEL_GAP
private val SHRUG_LIFT = 2.dp
private val EMPTY_TOP = 16.dp
private val EMPTY_BOTTOM = 3.dp
