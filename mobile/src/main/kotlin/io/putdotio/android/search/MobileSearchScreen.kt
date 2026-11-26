package io.putdotio.android.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.putdotio.android.LoadNextPageNearEnd
import io.putdotio.android.LoadNextPageNow
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileErrorState
import io.putdotio.android.RetryWhenOnline
import io.putdotio.android.isUnreachable
import io.putdotio.android.MobileRow
import io.putdotio.android.MobileSectionLabel
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.RowText
import io.putdotio.android.design.putioMono
import io.putdotio.android.design.shown
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioEmptyState
import io.putdotio.android.monoArguments
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.MobileFilesRow
import io.putdotio.android.files.FilesRowText
import io.putdotio.android.files.MobileFilesRowPlace
import io.putdotio.android.files.rememberFilesRowText
import io.putdotio.android.files.MobileFilesSkeleton
import io.putdotio.android.files.mobileMessage
import io.putdotio.android.neutralTextButtonColors
import androidx.compose.foundation.layout.heightIn
import io.putdotio.android.MobileIconButton

internal const val MOBILE_SEARCH_RESULTS_TAG = "mobile-search-results"
internal const val MOBILE_SEARCH_FILTERS_TAG = "mobile-search-filters"

@Composable
internal fun MobileSearchScreen(
    searchState: SearchState,
    recentSearchFailure: PutioFailure?,
    onSearchResult: (FilesItem) -> Unit,
    onSearchNextPage: () -> Unit,
    onSearchRetry: () -> Unit,
    onRecentSearch: (SearchTerm) -> Unit,
    onRecentEdit: (RecentSearchEdit) -> Unit,
    onRecentRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onFilterSelected: (SearchFilter) -> Unit = {},
    folderNames: Map<FilesItemId, String> = emptyMap(),
    downloadStatus: (FilesItemId) -> DownloadStatus? = { null },
    onResultActions: ((FilesItem) -> Unit)? = null,
) {
    val content = searchState.content
    Column(modifier = modifier.fillMaxSize()) {
        val nothingAtAll = content is SearchContent.Empty && content.paging == SearchPaging.Complete &&
            searchState.filter == SearchFilter.All
        if (!nothingAtAll) {
            MobileSearchFilters(searchState.filter, onFilterSelected)
        }
        if (recentSearchFailure != null) {
            MobileRecentSearchFailure(
                failure = recentSearchFailure,
                onRetry = onRecentRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
        val fill = Modifier.weight(1f)
        when (content) {
            SearchContent.Idle -> MobileRecentSearches(searchState.recentTerms, onRecentSearch, onRecentEdit, fill)
            is SearchContent.Debouncing, is SearchContent.Loading ->
                MobileFilesSkeleton(fill, description = stringResource(R.string.mobile_search_loading))
            is SearchContent.Empty ->
                MobileSearchEmpty(content.term, content.paging, onSearchNextPage, onSearchRetry, fill)
            is SearchContent.Ready ->
                MobileSearchResults(
                    content = content,
                    rows = MobileSearchRows(folderNames, downloadStatus, onSearchResult, onResultActions),
                    onNextPage = onSearchNextPage,
                    onRetry = onSearchRetry,
                    modifier = fill,
                )
            is SearchContent.Failed -> {
                val offline = content.failure.isUnreachable
                if (offline) RetryWhenOnline(content.failure, onSearchRetry)
                MobileErrorState(
                    title = stringResource(R.string.mobile_search_error_title),
                    message = content.failure.mobileMessage(),
                    retryLabel = stringResource(R.string.mobile_action_retry),
                    onRetry = onSearchRetry,
                    modifier = fill,
                    glyph = PutioEmptyGlyph.Offline.takeIf { offline },
                )
            }
        }
    }
}

@Composable
private fun MobileSearchFilters(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag(MOBILE_SEARCH_FILTERS_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchFilter.entries.forEach { filter ->
            val chosen = filter == selected
            FilterChip(
                selected = chosen,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filter.label)) },
                leadingIcon = if (chosen) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_ph_check),
                            contentDescription = null,
                            modifier = Modifier.size(CHIP_CHECK_SIZE),
                        )
                    }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = chosen,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

@get:StringRes
private val SearchFilter.label: Int
    get() = when (this) {
        SearchFilter.All -> R.string.mobile_search_filter_all
        SearchFilter.Folders -> R.string.mobile_search_filter_folders
        SearchFilter.Videos -> R.string.mobile_search_filter_videos
        SearchFilter.Audio -> R.string.mobile_search_filter_audio
        SearchFilter.Images -> R.string.mobile_search_filter_images
        SearchFilter.Documents -> R.string.mobile_search_filter_documents
        SearchFilter.Subtitles -> R.string.mobile_search_filter_subtitles
    }

@Composable
private fun MobileRecentSearchFailure(
    failure: PutioFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        BoxWithConstraints(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (maxWidth / LocalDensity.current.fontScale < 320.dp) {
                Column {
                    MobileRecentSearchFailureText(failure)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.mobile_action_retry)) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MobileRecentSearchFailureText(failure, Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.mobile_action_retry)) }
                }
            }
        }
    }
}

@Composable
private fun MobileRecentSearchFailureText(failure: PutioFailure, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.mobile_search_recent_error_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = failure.mobileMessage(),
            style = MaterialTheme.typography.bodySmall,
        )
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
    val muted = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item(key = 0, contentType = "header") {
            MobileRecentHeader(onClear = { onEdit(RecentSearchEdit.Clear) })
        }
        items(terms, key = SearchTerm::value, contentType = { "recent" }) { term ->
            MobileRow(
                leading = {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_clock_counter_clockwise),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(RECENT_GLYPH_SIZE),
                    )
                },
                headline = { Text(term.value, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailing = {
                    MobileIconButton(
                        stringResource(R.string.mobile_search_remove_named, term.value),
                        R.drawable.ic_ph_x,
                        onClick = { onEdit(RecentSearchEdit.Remove(term)) },
                        colors = muted,
                    )
                },
                modifier = Modifier.clickable(role = Role.Button) { onSearch(term) },
            )
        }
    }
}

@Composable
private fun MobileRecentHeader(onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.mobile_search_recent),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        TextButton(
            onClick = onClear,
            colors = neutralTextButtonColors(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.heightIn(min = MIN_TARGET),
        ) {
            Text(stringResource(R.string.mobile_search_clear_recent), style = MaterialTheme.typography.titleSmall)
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
    LoadNextPageNow(nextPage = paging as? SearchPaging.Available, onLoadNextPage = onNextPage)
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            propagateMinConstraints = true,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PutioEmptyState(
                    glyph = PutioEmptyGlyph.NoResults,
                    headline = stringResource(R.string.mobile_search_no_results_title),
                    message = noResultsMessage(term),
                )
            }
        }
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
private fun noResultsMessage(term: SearchTerm): AnnotatedString {
    val message = stringResource(R.string.mobile_search_no_results_message, TERM_MARK)
    val emphasis = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    val parts = message.split(TERM_MARK, limit = 2)
    return buildAnnotatedString {
        append(parts.first())
        withStyle(emphasis) { append(term.shown) }
        parts.getOrNull(1)?.let(::append)
    }
}

private class MobileSearchRows(
    val folderNames: Map<FilesItemId, String>,
    val downloadStatus: (FilesItemId) -> DownloadStatus?,
    val onResult: (FilesItem) -> Unit,
    val onActions: ((FilesItem) -> Unit)?,
)

@Composable
private fun MobileSearchResults(
    content: SearchContent.Ready,
    rows: MobileSearchRows,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowText = rememberFilesRowText(content.items, dated = false)
    val shown = rowText.shown(content, content.items) ?: return
    MobileSearchResultList(shown, rowText, rows, onNextPage, onRetry, modifier)
}

@Composable
private fun MobileSearchResultList(
    content: SearchContent.Ready,
    rowText: RowText<FilesItem, FilesRowText>,
    rows: MobileSearchRows,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val (folders, files) = remember(content.items) { content.items.partition(FilesItem::isFolder) }
    val rootName = stringResource(R.string.mobile_destination_files)
    val listState = rememberLazyListState()
    LoadNextPageNearEnd(listState, nextPage = content.paging as? SearchPaging.Available, onLoadNextPage = onNextPage)
    LazyColumn(state = listState, modifier = modifier.testTag(MOBILE_SEARCH_RESULTS_TAG)) {
        content.total?.let { total ->
            item(key = "search-total", contentType = "total") { MobileSearchTotal(total) }
        }
        resultGroup(R.string.mobile_search_group_folders, "folders", folders) { item ->
            MobileSearchResultRow(item, rows, rootName, rowText[item])
        }
        resultGroup(R.string.mobile_search_group_files, "files", files) { item ->
            MobileSearchResultRow(item, rows, rootName, rowText[item])
        }
        if (content.paging != SearchPaging.Complete) {
            item(key = "search-paging", contentType = "paging") {
                MobileSearchPaging(content.paging, onNextPage, onRetry, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MobileSearchTotal(total: SearchTotal) {
    val shown = total.shown(LocalConfiguration.current.locales[0])
    val style = MaterialTheme.typography.bodySmall
    val template = pluralStringResource(R.plurals.mobile_search_total, total.count, VALUE_SLOT)
    Text(
        text = monoArguments(template, style.putioMono().toSpanStyle(), shown),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

private const val VALUE_SLOT = "%1\$s"

private fun LazyListScope.resultGroup(
    @StringRes title: Int,
    key: String,
    items: List<FilesItem>,
    row: @Composable (FilesItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "group-$key", contentType = "header") {
        MobileSectionLabel(text = stringResource(title))
    }
    items(items, key = { it.id.value }, contentType = { "row" }) { row(it) }
}

@Composable
private fun MobileSearchResultRow(item: FilesItem, rows: MobileSearchRows, rootName: String, text: FilesRowText) {
    val folderName = item.parentId?.let { parent ->
        if (parent == FilesFolder.Root.id) rootName else rows.folderNames[parent]
    }
    MobileFilesRow(
        item = item,
        onClick = { rows.onResult(item) },
        onClickLabel = stringResource(
            if (item.isPlayable) R.string.mobile_files_play_media else R.string.mobile_search_open_result,
            item.name,
        ),
        onActions = rows.onActions?.let { open -> { open(item) } },
        downloadStatus = rows.downloadStatus(item.id),
        place = MobileFilesRowPlace.SearchResult(folderName),
        text = text,
    )
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

private const val TERM_MARK = ""
private val CHIP_CHECK_SIZE = 16.dp
private val RECENT_GLYPH_SIZE = 24.dp
private val MIN_TARGET = 48.dp
