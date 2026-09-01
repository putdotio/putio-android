package io.putdotio.android

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import java.time.Instant
import java.time.ZoneId

internal const val MOBILE_SEARCH_FIELD_TAG = "mobile-search-field"
internal const val MOBILE_SEARCH_RESULTS_TAG = "mobile-search-results"
internal const val MOBILE_HISTORY_LIST_TAG = "mobile-history-list"

@Composable
internal fun MobileSearchHistoryScreen(
    searchState: SearchState,
    historyState: HistoryState,
    recentSearchFailure: FilesFailure?,
    onSearchQueryChanged: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchResult: (FilesItem) -> Unit,
    onSearchNextPage: () -> Unit,
    onSearchRetry: () -> Unit,
    onRecentSearch: (SearchTerm) -> Unit,
    onRecentEdit: (RecentSearchEdit) -> Unit,
    onRecentRetry: () -> Unit,
    onHistoryEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(SEARCH_TAB) }
    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == SEARCH_TAB,
                onClick = { selectedTab = SEARCH_TAB },
                text = { Text(stringResource(R.string.mobile_search_tab)) },
            )
            Tab(
                selected = selectedTab == HISTORY_TAB,
                onClick = { selectedTab = HISTORY_TAB },
                text = { Text(stringResource(R.string.mobile_history_tab)) },
            )
        }
        if (selectedTab == SEARCH_TAB) {
            MobileSearchContent(
                state = searchState,
                onQueryChanged = onSearchQueryChanged,
                onSubmit = onSearchSubmit,
                onResult = onSearchResult,
                onNextPage = onSearchNextPage,
                onRetry = onSearchRetry,
                onRecentSearch = onRecentSearch,
                onRecentEdit = onRecentEdit,
                recentSearchFailure = recentSearchFailure,
                onRecentRetry = onRecentRetry,
                modifier = Modifier.weight(1f),
            )
        } else {
            MobileHistoryContent(
                state = historyState,
                onEvent = onHistoryEvent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MobileSearchContent(
    state: SearchState,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onResult: (FilesItem) -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    onRecentSearch: (SearchTerm) -> Unit,
    onRecentEdit: (RecentSearchEdit) -> Unit,
    recentSearchFailure: FilesFailure?,
    onRecentRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag(MOBILE_SEARCH_FIELD_TAG),
            label = { Text(stringResource(R.string.mobile_search_label)) },
            placeholder = { Text(stringResource(R.string.mobile_search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )

        if (recentSearchFailure != null) {
            MobileRecentSearchFailure(
                failure = recentSearchFailure,
                onRetry = onRecentRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }

        when (val content = state.content) {
            SearchContent.Idle ->
                MobileRecentSearches(
                    terms = state.recentTerms,
                    onSearch = onRecentSearch,
                    onEdit = onRecentEdit,
                    modifier = Modifier.weight(1f),
                )

            is SearchContent.Debouncing ->
                MobileLoadingState(
                    message = stringResource(R.string.mobile_search_loading),
                    modifier = Modifier.weight(1f),
                )
            is SearchContent.Loading ->
                MobileLoadingState(
                    message = stringResource(R.string.mobile_search_loading),
                    modifier = Modifier.weight(1f),
                )

            is SearchContent.Empty ->
                MobileSearchEmpty(
                    term = content.term,
                    paging = content.paging,
                    onNextPage = onNextPage,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f),
                )

            is SearchContent.Ready ->
                MobileSearchResults(
                    items = content.items,
                    paging = content.paging,
                    onResult = onResult,
                    onNextPage = onNextPage,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f),
                )

            is SearchContent.Failed ->
                MobileErrorState(
                    title = stringResource(R.string.mobile_search_error_title),
                    message = stringResource(content.failure.mobileMessageResource()),
                    retryLabel = stringResource(R.string.mobile_action_retry),
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

@Composable
private fun MobileRecentSearchFailure(
    failure: FilesFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mobile_search_recent_error_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(failure.mobileMessageResource()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        }
    }
}

@Composable
private fun MobileRecentSearches(
    terms: List<SearchTerm>,
    onSearch: (SearchTerm) -> Unit,
    onEdit: (RecentSearchEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (terms.isEmpty()) {
        MobileEmptyState(
            title = stringResource(R.string.mobile_search_start_title),
            message = stringResource(R.string.mobile_search_start_message),
            modifier = modifier,
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mobile_search_recent),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { onEdit(RecentSearchEdit.Clear) }) {
                    Text(stringResource(R.string.mobile_search_clear_recent))
                }
            }
        }
        items(terms, key = SearchTerm::value) { term ->
            ListItem(
                headlineContent = { Text(term.value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.clickable(role = Role.Button) { onSearch(term) },
                trailingContent = {
                    TextButton(onClick = { onEdit(RecentSearchEdit.Remove(term)) }) {
                        Text(stringResource(R.string.mobile_action_remove))
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun MobileSearchEmpty(
    term: SearchTerm,
    paging: SearchPaging,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        MobileEmptyState(
            title = stringResource(R.string.mobile_search_no_results_title),
            message = stringResource(R.string.mobile_search_no_results_message, term.value),
        )
        MobileSearchPaging(
            paging = paging,
            onNextPage = onNextPage,
            onRetry = onRetry,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun MobileSearchResults(
    items: List<FilesItem>,
    paging: SearchPaging,
    onResult: (FilesItem) -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.testTag(MOBILE_SEARCH_RESULTS_TAG)) {
        items(items, key = { it.id.value }) { item ->
            MobileFilesRow(
                item = item,
                onClick = { onResult(item) },
                onClickLabel = stringResource(R.string.mobile_search_open_result, item.name),
            )
            HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
        }
        if (paging != SearchPaging.Complete) {
            item(key = "search-paging") {
                MobileSearchPaging(paging, onNextPage, onRetry, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MobileSearchPaging(
    paging: SearchPaging,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (paging) {
        is SearchPaging.Available ->
            TextButton(onClick = onNextPage, modifier = modifier) {
                Text(stringResource(R.string.mobile_files_load_more))
            }
        is SearchPaging.Loading ->
            Row(
                modifier = modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.mobile_search_loading_more))
            }
        is SearchPaging.Failed ->
            Column(modifier = modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.mobile_search_paging_error))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.mobile_action_retry)) }
            }
        SearchPaging.Complete -> Unit
    }
}

@Composable
private fun MobileHistoryContent(
    state: HistoryState,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val content = state.content) {
        HistoryContent.Disabled ->
            MobileEmptyState(
                title = stringResource(R.string.mobile_history_disabled_title),
                message = stringResource(R.string.mobile_history_disabled_message),
                modifier = modifier,
            )
        is HistoryContent.Loading ->
            MobileLoadingState(stringResource(R.string.mobile_history_loading), modifier)
        HistoryContent.Empty ->
            MobileEmptyState(
                title = stringResource(R.string.mobile_history_empty_title),
                message = stringResource(R.string.mobile_history_empty_message),
                modifier = modifier,
            )
        is HistoryContent.Failed ->
            MobileErrorState(
                title = stringResource(R.string.mobile_history_error_title),
                message = stringResource(content.failure.mobileMessageResource()),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = { onEvent(HistoryEvent.Retry) },
                modifier = modifier,
            )
        is HistoryContent.Ready ->
            MobileHistoryList(content, state.clearing, onEvent, modifier)
    }
    MobileHistoryDialog(state.clearing, onEvent)
}

@Composable
private fun MobileHistoryList(
    content: HistoryContent.Ready,
    clearing: HistoryClearing,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = remember(content.items) { content.items.groupBy(HistoryItem::dateKey) }
    LazyColumn(modifier = modifier.testTag(MOBILE_HISTORY_LIST_TAG)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mobile_history_recent),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(
                    onClick = { onEvent(HistoryEvent.RequestClear) },
                    enabled = clearing == HistoryClearing.Idle,
                ) {
                    Text(stringResource(R.string.mobile_history_clear))
                }
            }
        }
        grouped.forEach { (date, events) ->
            item(key = "date-$date") {
                Text(
                    text = date,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(events, key = { it.id.value }) { item ->
                val fileId = item.kind.navigableFileId()
                ListItem(
                    headlineContent = { Text(item.title()) },
                    supportingContent = {
                        Text(stringResource(R.string.mobile_history_metadata, item.kindLabel(), item.timeLabel()))
                    },
                    modifier = if (fileId == null) Modifier else Modifier.clickable(role = Role.Button) {
                        onEvent(HistoryEvent.OpenFile(fileId))
                    },
                    trailingContent = if (fileId == null) null else {
                        {
                            TextButton(onClick = { onEvent(HistoryEvent.OpenFile(fileId)) }) {
                                Text(stringResource(R.string.mobile_history_open_file))
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
        if (content.paging != HistoryPaging.Complete) {
            item(key = "history-paging") {
                MobileHistoryPaging(content.paging, onEvent, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MobileHistoryPaging(
    paging: HistoryPaging,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (paging) {
        is HistoryPaging.Available ->
            TextButton(onClick = { onEvent(HistoryEvent.LoadNextPage) }, modifier = modifier) {
                Text(stringResource(R.string.mobile_history_load_more))
            }
        is HistoryPaging.Loading ->
            Row(
                modifier = modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.mobile_history_loading_more))
            }
        is HistoryPaging.Failed ->
            Column(modifier = modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.mobile_history_paging_error))
                TextButton(onClick = { onEvent(HistoryEvent.Retry) }) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }
        HistoryPaging.Complete -> Unit
    }
}

@Composable
private fun MobileHistoryDialog(
    clearing: HistoryClearing,
    onEvent: (HistoryEvent) -> Unit,
) {
    when (clearing) {
        HistoryClearing.AwaitingConfirmation ->
            AlertDialog(
                onDismissRequest = { onEvent(HistoryEvent.DismissClear) },
                title = { Text(stringResource(R.string.mobile_history_clear_title)) },
                text = { Text(stringResource(R.string.mobile_history_clear_message)) },
                confirmButton = {
                    TextButton(onClick = { onEvent(HistoryEvent.ConfirmClear) }) {
                        Text(stringResource(R.string.mobile_history_clear_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(HistoryEvent.DismissClear) }) {
                        Text(stringResource(R.string.mobile_action_cancel))
                    }
                },
            )
        is HistoryClearing.Clearing ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.mobile_history_clearing)) },
                text = { CircularProgressIndicator() },
                confirmButton = {},
            )
        is HistoryClearing.Failed ->
            AlertDialog(
                onDismissRequest = { onEvent(HistoryEvent.DismissClear) },
                title = { Text(stringResource(R.string.mobile_history_clear_error_title)) },
                text = { Text(stringResource(clearing.failure.mobileMessageResource())) },
                confirmButton = {
                    TextButton(onClick = { onEvent(HistoryEvent.DismissClear) }) {
                        Text(stringResource(R.string.mobile_action_ok))
                    }
                },
            )
        HistoryClearing.Idle -> Unit
    }
}

private fun HistoryItem.dateKey(): String =
    runCatching {
        Instant.parse(createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrDefault(createdAt)

@Composable
private fun HistoryItem.timeLabel(): String {
    val context = LocalContext.current
    return runCatching {
        DateUtils.formatDateTime(
            context,
            Instant.parse(createdAt).toEpochMilli(),
            DateUtils.FORMAT_SHOW_TIME,
        )
    }.getOrDefault(createdAt)
}

@Composable
private fun HistoryItem.title(): String =
    when (val value = kind) {
        is HistoryEventKind.File -> value.name ?: stringResource(R.string.mobile_history_file)
        is HistoryEventKind.Transfer -> value.name ?: stringResource(R.string.mobile_history_transfer)
        is HistoryEventKind.Other -> value.title ?: value.type
    }

@Composable
private fun HistoryItem.kindLabel(): String =
    when (kind) {
        is HistoryEventKind.File -> stringResource(R.string.mobile_history_shared_file)
        is HistoryEventKind.Transfer -> stringResource(R.string.mobile_history_completed_transfer)
        is HistoryEventKind.Other -> stringResource(R.string.mobile_history_activity)
    }

private fun HistoryEventKind.navigableFileId(): HistoryFileId? =
    when (this) {
        is HistoryEventKind.File -> id
        is HistoryEventKind.Transfer -> fileId
        is HistoryEventKind.Other -> null
    }

private const val SEARCH_TAB = 0
private const val HISTORY_TAB = 1
