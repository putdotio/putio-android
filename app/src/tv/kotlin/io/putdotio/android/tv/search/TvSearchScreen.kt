package io.putdotio.android.tv.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.AssistChip
import androidx.tv.material3.AssistChipDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.android.tv.files.TvFilesRow
import io.putdotio.android.tv.files.tvMessage
import kotlinx.coroutines.flow.first

internal const val TV_SEARCH_FIELD_TAG = "tv-search-field"
internal const val TV_SEARCH_RESULTS_TAG = "tv-search-results"

internal class TvSearchActions(
    val onQueryChanged: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onResult: (FilesItem) -> Unit,
    val onNextPage: () -> Unit,
    val onRetry: () -> Unit,
    val onRecentSearch: (SearchTerm) -> Unit,
    val onRecentEdit: (RecentSearchEdit) -> Unit,
    val onRecentRetry: () -> Unit,
)

/**
 * Search per the oracle (07-07c): an M3 pill field that summons the system IME on
 * Center, recent-query chips replayed as typed, and results in the standard rows.
 * The app draws no keyboard and no mic. Focus enters on the field; Down reaches the
 * chips and then the rows, Up and Left return to the field and the drawer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvSearchScreen(
    state: SearchState,
    actions: TvSearchActions,
    modifier: Modifier = Modifier,
    /** A failure that is not the search itself: the recent-search store or a blocked open. */
    notice: FilesFailure? = null,
) {
    // The shell asks the pane for focus on entry, and Right from the drawer enters it by
    // direction. Both land on the section that held focus last, the field the first time.
    val fieldFocus = remember { FocusRequester() }
    val entryTarget = remember { mutableStateOf(fieldFocus) }
    val owner = remember { TvSearchFocusOwner(entryTarget, fieldFocus) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .focusProperties { enter = { entryTarget.value } }
            .focusGroup(),
    ) {
        TvSearchField(
            query = state.query,
            onQueryChanged = actions.onQueryChanged,
            onSubmit = actions.onSubmit,
            modifier = owner.section(fieldFocus).focusRequester(fieldFocus),
        )
        // Composed while there are terms; when the last one is removed the section disposes
        // and its section() hands the entry target back, but focus itself must be re-placed.
        val hadRecent = remember { mutableStateOf(false) }
        LaunchedEffect(state.recentTerms.isEmpty()) {
            if (state.recentTerms.isEmpty() && hadRecent.value && entryTarget.value === fieldFocus) {
                withFrameNanos {}
                fieldFocus.requestFocus()
            }
            hadRecent.value = state.recentTerms.isNotEmpty()
        }
        if (state.recentTerms.isNotEmpty()) {
            TvRecentSearches(
                terms = state.recentTerms,
                onSearch = actions.onRecentSearch,
                onRemove = { actions.onRecentEdit(RecentSearchEdit.Remove(it)) },
                owner = owner,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (notice != null) {
            TvSearchNotice(notice, onRetry = actions.onRecentRetry, owner = owner)
        }
        when (val content = state.content) {
            SearchContent.Idle ->
                TvStatusScreen(stringResource(R.string.tv_search_start), modifier = Modifier.weight(1f))
            is SearchContent.Debouncing, is SearchContent.Loading ->
                TvStatusScreen(stringResource(R.string.tv_search_searching), modifier = Modifier.weight(1f))
            is SearchContent.Empty ->
                Column(modifier = Modifier.weight(1f)) {
                    TvStatusScreen(stringResource(R.string.tv_search_no_results), modifier = Modifier.weight(1f))
                    TvSearchPaging(content.paging, actions.onNextPage, actions.onRetry, owner)
                }
            is SearchContent.Ready ->
                TvSearchResults(
                    items = content.items,
                    paging = content.paging,
                    onResult = actions.onResult,
                    onNextPage = actions.onNextPage,
                    onRetry = actions.onRetry,
                    owner = owner,
                    modifier = Modifier.weight(1f),
                )
            is SearchContent.Failed -> {
                val retryFocus = remember { FocusRequester() }
                TvStatusScreen(
                    title = stringResource(R.string.tv_search_error_title),
                    message = stringResource(content.failure.tvMessage()),
                    action = stringResource(R.string.tv_files_retry),
                    onAction = {
                        // The button leaves with the failure; the field takes over before it goes.
                        fieldFocus.requestFocus()
                        actions.onRetry()
                    },
                    modifier = owner.section(retryFocus).weight(1f),
                    actionFocus = retryFocus,
                    // The field keeps focus; Down reaches Try again like any other row.
                    claimFocus = false,
                )
            }
        }
    }
}

/**
 * Which section of the pane focus should return to. Sections register themselves while
 * they hold focus and hand the target back to the field when they leave composition.
 */
private class TvSearchFocusOwner(
    private val entryTarget: MutableState<FocusRequester>,
    private val fieldFocus: FocusRequester,
) {
    fun focusField() = fieldFocus.requestFocus()

    /** Tracks focus for a section; the requester itself is attached where focus should land. */
    @Composable
    fun section(requester: FocusRequester): Modifier {
        DisposableEffect(requester) {
            onDispose { if (entryTarget.value === requester) entryTarget.value = fieldFocus }
        }
        return Modifier.onFocusChanged { if (it.hasFocus) entryTarget.value = requester }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The controller owns the query; the field keeps its own selection and follows the
    // controller when a chip or a submit rewrites the text.
    val state = rememberTextFieldState(query)
    val currentQuery by rememberUpdatedState(query)
    val currentOnQueryChanged by rememberUpdatedState(onQueryChanged)
    LaunchedEffect(state) {
        // Only edits made here go up; text the controller already knows is not echoed.
        snapshotFlow { state.text.toString() }.collect { if (it != currentQuery) currentOnQueryChanged(it) }
    }
    LaunchedEffect(query) {
        if (state.text.toString() != query) state.setTextAndPlaceCursorAtEnd(query)
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The IME appears only on Center, never on focus alone: the pane opens with the field
    // focused and the oracle idle state shows no keyboard. Center flips the option, which
    // restarts the field's input session with the keyboard shown; that session is what
    // gives Gboard TV the D-pad, and a plain show request from outside it does not. The
    // flag is dropped when the IME goes away so the next Center can raise it again.
    var keyboardRequested by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible, focused) { if (!imeVisible || !focused) keyboardRequested = false }
    val label = stringResource(R.string.tv_search_field)
    val border = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.border
    BasicTextField(
        state = state,
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = keyboardRequested),
        onKeyboardAction = {
            keyboard?.hide()
            onSubmit()
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .testTag(TV_SEARCH_FIELD_TAG)
            // A five-way pad moves between controls; the IME, once summoned with Center,
            // takes the keys itself. Left and Right stay with the cursor while it has
            // text to cross. Enter reaches the field and runs the search action.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val selection = state.selection
                when (event.key) {
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionLeft ->
                        selection.collapsed && selection.start == 0 && focusManager.moveFocus(FocusDirection.Left)
                    Key.DirectionRight ->
                        selection.collapsed && selection.end == state.text.length &&
                            focusManager.moveFocus(FocusDirection.Right)
                    Key.DirectionCenter -> {
                        keyboardRequested = true
                        true
                    }
                    else -> false
                }
            },
        decorator = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .border(2.dp, border, CircleShape)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_magnifying_glass),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (state.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tv_search_placeholder),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRecentSearches(
    terms: List<SearchTerm>,
    onSearch: (SearchTerm) -> Unit,
    onRemove: (SearchTerm) -> Unit,
    owner: TvSearchFocusOwner,
    modifier: Modifier = Modifier,
) {
    // Down from the full-width field lands on the geometrically nearest chip; first entry
    // should land on the newest term instead, and later entries on the chip left last.
    val firstChip = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    // A removed chip takes focus with it; the newest remaining chip picks it up, or the
    // field when the row is now empty. Read after the frame so the row has re-laid out.
    var focusedTerm by remember { mutableStateOf<SearchTerm?>(null) }
    LaunchedEffect(terms) {
        val lost = focusedTerm ?: return@LaunchedEffect
        if (lost in terms) return@LaunchedEffect
        focusedTerm = null
        withFrameNanos {}
        if (terms.isEmpty()) owner.focusField() else firstChip.requestFocus()
    }
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .then(owner.section(rowFocus))
            .focusRequester(rowFocus)
            .focusRestorer(firstChip)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(terms, key = { _, term -> term.value }) { index, term ->
            val label = stringResource(R.string.tv_search_recent_term, term.value)
            AssistChip(
                onClick = { onSearch(term) },
                onLongClick = { onRemove(term) },
                // Focused chips fill `primary` like buttons: the stock focused pair binds to
                // the same light token in the put.io scheme, so the label would vanish.
                colors = AssistChipDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.primary,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                    pressedContainerColor = MaterialTheme.colorScheme.primary,
                    pressedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_clock_counter_clockwise),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier
                    .then(if (index == 0) Modifier.focusRequester(firstChip) else Modifier)
                    .onFocusChanged { if (it.isFocused) focusedTerm = term }
                    .semantics { contentDescription = label },
            ) {
                // As typed: the term is what the user searched, not a normalized key.
                Text(term.value)
            }
        }
    }
}

@Composable
private fun TvSearchNotice(
    failure: FilesFailure,
    onRetry: () -> Unit,
    owner: TvSearchFocusOwner,
) {
    val retryFocus = remember { FocusRequester() }
    Row(
        modifier = owner.section(retryFocus)
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val message = if (failure == FilesFailure.NavigationBlocked) {
            stringResource(R.string.tv_error_navigation_blocked)
        } else {
            stringResource(R.string.tv_search_recent_error)
        }
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failure != FilesFailure.NavigationBlocked) {
            TvButton(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                Text(stringResource(R.string.tv_files_retry))
            }
        }
    }
}

@Composable
private fun TvSearchResults(
    items: List<FilesItem>,
    paging: SearchPaging,
    onResult: (FilesItem) -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    owner: TvSearchFocusOwner,
    modifier: Modifier = Modifier,
) {
    val firstRow = remember { FocusRequester() }
    val lastRow = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    // The paging control leaves the list when the last page lands; if it still held focus,
    // the last row takes over once it is on screen. A move to a row or the drawer clears the
    // latch first; the blur its disposal reports arrives after this composition decides.
    val pagingHeldFocus = remember { mutableStateOf(false) }
    val handOffToLastRow = remember { mutableStateOf(false) }
    // Decided during composition: the restorer answers the removed paging node in the same
    // frame, so its fallback must already name the last row rather than the first.
    if (paging == SearchPaging.Complete && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    val listState = rememberLazyListState()
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val lastId = items.last().id.value
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == lastId }) listState.scrollToItem(items.lastIndex)
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == lastId } }.first { it }
        withFrameNanos {}
        lastRow.requestFocus()
        handOffToLastRow.value = false
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .then(owner.section(listFocus))
            .focusRequester(listFocus)
            .focusRestorer(if (handOffToLastRow.value) lastRow else firstRow)
            .focusGroup()
            .testTag(TV_SEARCH_RESULTS_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id.value }) { index, item ->
            TvFilesRow(
                item = item,
                onClick = { onResult(item) },
                label = stringResource(R.string.tv_search_open_result, item.name),
                modifier = Modifier
                    .then(if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
                    .then(if (index == items.lastIndex) Modifier.focusRequester(lastRow) else Modifier),
            )
        }
        if (paging != SearchPaging.Complete) {
            item(key = TV_SEARCH_PAGING_KEY) {
                TvSearchPaging(
                    paging = paging,
                    onNextPage = onNextPage,
                    onRetry = onRetry,
                    buttonModifier = Modifier.onFocusChanged { pagingHeldFocus.value = it.isFocused },
                )
            }
        }
    }
}

@Composable
private fun TvSearchPaging(
    paging: SearchPaging,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    owner: TvSearchFocusOwner? = null,
    buttonModifier: Modifier = Modifier,
) {
    val pagingFocus = remember { FocusRequester() }
    // One button across every paging phase, so the node that holds focus survives the
    // transition from Load more to loading to a retry.
    val label = when (paging) {
        is SearchPaging.Available -> R.string.tv_search_load_more
        is SearchPaging.Loading -> R.string.tv_search_loading_more
        is SearchPaging.Failed -> R.string.tv_files_retry
        SearchPaging.Complete -> null
    } ?: return
    Row(
        modifier = (owner?.section(pagingFocus) ?: Modifier)
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paging is SearchPaging.Failed) {
            Text(stringResource(R.string.tv_search_paging_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TvButton(
            onClick = {
                when (paging) {
                    is SearchPaging.Available -> onNextPage()
                    is SearchPaging.Failed -> onRetry()
                    is SearchPaging.Loading, SearchPaging.Complete -> Unit
                }
            },
            modifier = buttonModifier.focusRequester(pagingFocus),
        ) {
            Text(stringResource(label))
        }
    }
}

private const val TV_SEARCH_PAGING_KEY = "tv-search-paging"
