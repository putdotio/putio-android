package io.putdotio.android.tv.trash

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.trash.TrashAction
import io.putdotio.android.trash.TrashActionCheck
import io.putdotio.android.trash.TrashActionOutcome
import io.putdotio.android.trash.TrashActionSubmission
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashRestoreCheck
import io.putdotio.android.trash.TrashRestoreOutcome
import io.putdotio.android.trash.TrashRestoreSubmission
import io.putdotio.android.trash.TrashState
import io.putdotio.android.trash.parseTrashTimestamp
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.android.tv.files.tvMessage
import kotlinx.coroutines.flow.first

internal const val TV_TRASH_LIST_TAG = "tv-trash-list"
internal const val TV_TRASH_ROW_TAG = "tv-trash-row"

/**
 * Trash per the oracle (13), reached from Account: the title with Refresh, Restore all,
 * and Empty on the right, then the deleted items in the standard rows. Center on a row
 * offers Restore and Delete permanently; every mutation confirms first and reports its
 * outcome above the list, with Check status or Check trash while it is unconfirmed.
 * Focus enters on the first row, or Refresh while there are none.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvTrashScreen(
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    modifier: Modifier = Modifier,
    /** Changes with the signed-in session so one account's list position never greets the next. */
    sessionKey: Any? = null,
) = key(sessionKey) {
    val content = state.content
    val loaded = content as? TrashContent.Loaded
    val hasRows = loaded != null && loaded.items.isNotEmpty()
    val refreshFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val paneHasFocus = remember { mutableStateOf(true) }
    val entryTarget = remember { mutableStateOf(refreshFocus) }
    val rowsPresent = rememberUpdatedState(hasRows)
    val owner = remember {
        TvPaneFocusOwner(entryTarget, paneHasFocus) { if (rowsPresent.value) listFocus else refreshFocus }
    }
    // Refresh is the one node that outlives every content change; it takes focus when the
    // rows go (an emptied trash, a reload) and a failed read focuses its own Try again.
    val headerOwnsFocus = content is TrashContent.Loading || (loaded != null && !hasRows)
    LaunchedEffect(headerOwnsFocus) {
        if (headerOwnsFocus && paneHasFocus.value) refreshFocus.requestFocus()
    }
    var chosenItemId by remember { mutableStateOf<Long?>(null) }
    val chosenItem = loaded?.items?.firstOrNull { it.id.value == chosenItemId }
    val dialogOpen = chosenItem != null || state.confirmation != null || state.actionConfirmation != null
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) {
        val closing = dialogWasOpen.value && !dialogOpen
        dialogWasOpen.value = dialogOpen
        if (!closing || !paneHasFocus.value) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) entryTarget.value.requestFocus()
    }
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
        TvTrashHeader(
            onRefresh = { onEvent(TrashEvent.Refresh) },
            onRestoreAll = { onEvent(TrashEvent.SelectRestoreAll) },
            onEmpty = { onEvent(TrashEvent.SelectEmpty) },
            refreshModifier = owner.section(refreshFocus).focusRequester(refreshFocus),
        )
        val enabled = state.authenticationFailure == null
        state.restoreOutcome?.let { TvTrashRestoreOutcome(it, enabled, onEvent, owner) }
        state.actionOutcome?.let { TvTrashActionOutcome(it, enabled, onEvent, owner) }
        loaded?.refreshFailure?.let { failure ->
            TvTrashNotice(
                text = stringResource(R.string.tv_trash_refresh_error) + " " + stringResource(failure.tvMessage()),
                action = stringResource(R.string.tv_files_retry),
                onAction = { onEvent(TrashEvent.Refresh) },
                owner = owner,
            )
        }
        when (content) {
            TrashContent.Loading ->
                TvStatusScreen(stringResource(R.string.tv_trash_loading), modifier = Modifier.weight(1f))
            is TrashContent.Error -> {
                DisposableEffect(Unit) {
                    entryTarget.value = retryFocus
                    onDispose {}
                }
                TvStatusScreen(
                    title = stringResource(R.string.tv_trash_error_title),
                    message = stringResource(content.failure.tvMessage()),
                    action = stringResource(R.string.tv_files_retry),
                    onAction = { onEvent(TrashEvent.Retry) },
                    modifier = owner.section(retryFocus).weight(1f),
                    actionFocus = retryFocus,
                    claimFocus = paneHasFocus.value,
                )
            }
            is TrashContent.Loaded ->
                if (!hasRows) {
                    TvTrashEmpty(modifier = Modifier.weight(1f))
                } else {
                    DisposableEffect(Unit) {
                        entryTarget.value = listFocus
                        onDispose {}
                    }
                    TvTrashList(
                        content = content,
                        onChoose = { chosenItemId = it.id.value },
                        onNextPage = { onEvent(TrashEvent.LoadNextPage) },
                        onRetry = { onEvent(TrashEvent.Retry) },
                        owner = owner,
                        listFocus = listFocus,
                        paneHasFocus = paneHasFocus,
                        modifier = Modifier.weight(1f),
                    )
                }
        }
    }
    chosenItem?.let { TvTrashItemDialog(it, state, onEvent, onDismiss = { chosenItemId = null }) }
    TvTrashConfirmations(state, onEvent)
}

@Composable
private fun TvTrashHeader(
    onRefresh: () -> Unit,
    onRestoreAll: () -> Unit,
    onEmpty: () -> Unit,
    refreshModifier: Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.tv_trash_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        // The buttons stay focusable in every state; the controller refuses what it cannot do.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusGroup()) {
            TvButton(onClick = onRefresh, modifier = refreshModifier) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_arrow_clockwise),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tv_trash_refresh))
            }
            TvButton(onClick = onRestoreAll) { Text(stringResource(R.string.tv_trash_restore_all)) }
            TvButton(onClick = onEmpty) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_trash),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tv_trash_empty_action))
            }
        }
    }
}

@Composable
private fun TvTrashEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_backspace),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.tv_trash_empty_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = stringResource(R.string.tv_trash_empty_message),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** One line above the list with an optional action; the action is a focus section. */
@Composable
private fun TvTrashNotice(
    text: String,
    action: String?,
    onAction: () -> Unit,
    owner: TvPaneFocusOwner,
    modifier: Modifier = Modifier,
) {
    val actionFocus = remember { FocusRequester() }
    // A group, so Up from a row measures against the full-width line rather than the
    // right-aligned button, which the header would otherwise beat on distance.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null) {
            TvButton(onClick = onAction, modifier = owner.section(actionFocus).focusRequester(actionFocus)) {
                Text(action)
            }
        }
    }
}

@Composable
private fun TvTrashRestoreOutcome(
    outcome: TrashRestoreOutcome,
    enabled: Boolean,
    onEvent: (TrashEvent) -> Boolean,
    owner: TvPaneFocusOwner,
) {
    val name = outcome.resolvedItem?.name ?: outcome.item.name
    val text = when {
        outcome.check == TrashRestoreCheck.AVAILABLE -> stringResource(R.string.tv_trash_restore_available, name)
        outcome.check == TrashRestoreCheck.CHECKING -> stringResource(R.string.tv_trash_checking)
        outcome.check == TrashRestoreCheck.UNAVAILABLE -> stringResource(R.string.tv_trash_restore_not_available, name)
        outcome.check == TrashRestoreCheck.FAILED && outcome.checkFailure != null ->
            stringResource(outcome.checkFailure.tvMessage())
        else -> when (outcome.submission) {
            TrashRestoreSubmission.SUBMITTING -> stringResource(R.string.tv_trash_submitting)
            TrashRestoreSubmission.ACKNOWLEDGED -> stringResource(R.string.tv_trash_restore_started, name)
            TrashRestoreSubmission.UNCERTAIN -> stringResource(R.string.tv_trash_restore_uncertain, name)
            TrashRestoreSubmission.REJECTED -> stringResource(R.string.tv_trash_restore_rejected, name) +
                (outcome.submissionFailure?.let { " " + stringResource(it.tvMessage()) } ?: "")
        }
    }
    TvTrashOutcomeNotice(
        text = text,
        submitting = outcome.submission == TrashRestoreSubmission.SUBMITTING,
        pending = outcome.isPending,
        checking = outcome.check == TrashRestoreCheck.CHECKING,
        checkLabel = stringResource(R.string.tv_trash_check_status),
        enabled = enabled,
        onCheck = { onEvent(TrashEvent.CheckRestore) },
        onDismiss = { onEvent(TrashEvent.DismissRestoreOutcome) },
        owner = owner,
    )
}

@Composable
private fun TvTrashActionOutcome(
    outcome: TrashActionOutcome,
    enabled: Boolean,
    onEvent: (TrashEvent) -> Boolean,
    owner: TvPaneFocusOwner,
) {
    TvTrashOutcomeNotice(
        text = outcome.tvText(),
        submitting = outcome.submission == TrashActionSubmission.SUBMITTING,
        pending = outcome.isPending,
        checking = outcome.check == TrashActionCheck.CHECKING,
        checkLabel = stringResource(R.string.tv_trash_check_trash),
        enabled = enabled,
        onCheck = { onEvent(TrashEvent.CheckAction) },
        onDismiss = { onEvent(TrashEvent.DismissActionOutcome) },
        owner = owner,
    )
}

@Composable
private fun TrashActionOutcome.tvText(): String {
    val name = (action as? TrashAction.DeleteItem)?.item?.name.orEmpty()
    return when {
        check == TrashActionCheck.CHECKING -> stringResource(R.string.tv_trash_checking)
        check == TrashActionCheck.VERIFIED -> when (action) {
            is TrashAction.DeleteItem -> stringResource(R.string.tv_trash_delete_verified, name)
            TrashAction.RestoreAll -> stringResource(R.string.tv_trash_restore_all_verified)
            TrashAction.Empty -> stringResource(R.string.tv_trash_empty_verified)
        }
        check == TrashActionCheck.INCONCLUSIVE -> when (action) {
            is TrashAction.DeleteItem -> stringResource(R.string.tv_trash_delete_inconclusive, name)
            else -> stringResource(R.string.tv_trash_restore_all_inconclusive)
        }
        check == TrashActionCheck.FAILED -> checkFailure?.let { stringResource(it.tvMessage()) }
            ?: when (action) {
                is TrashAction.DeleteItem -> stringResource(R.string.tv_trash_delete_still_present, name)
                else -> stringResource(R.string.tv_trash_empty_still_present)
            }
        else -> when (submission) {
            TrashActionSubmission.SUBMITTING -> stringResource(R.string.tv_trash_submitting)
            TrashActionSubmission.ACKNOWLEDGED -> when (action) {
                is TrashAction.DeleteItem -> stringResource(R.string.tv_trash_delete_started, name)
                TrashAction.RestoreAll -> stringResource(R.string.tv_trash_restore_all_started)
                TrashAction.Empty -> stringResource(R.string.tv_trash_empty_started)
            }
            TrashActionSubmission.UNCERTAIN -> stringResource(R.string.tv_trash_action_uncertain)
            TrashActionSubmission.REJECTED -> stringResource(R.string.tv_trash_action_rejected) +
                (submissionFailure?.let { " " + stringResource(it.tvMessage()) } ?: "")
        }
    }
}

/**
 * An outcome keeps one button across its life: Check while unconfirmed, OK once settled,
 * none while the request is in flight, so the node holding focus survives each step.
 */
@Composable
private fun TvTrashOutcomeNotice(
    text: String,
    submitting: Boolean,
    pending: Boolean,
    checking: Boolean,
    checkLabel: String,
    enabled: Boolean,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
    owner: TvPaneFocusOwner,
) {
    TvTrashNotice(
        text = text,
        action = when {
            submitting -> null
            pending -> checkLabel
            else -> stringResource(R.string.tv_trash_ok)
        },
        onAction = {
            when {
                submitting -> Unit
                pending -> if (enabled && !checking) onCheck()
                else -> onDismiss()
            }
        },
        owner = owner,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvTrashList(
    content: TrashContent.Loaded,
    onChoose: (TrashItem) -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    owner: TvPaneFocusOwner,
    listFocus: FocusRequester,
    paneHasFocus: State<Boolean>,
    modifier: Modifier = Modifier,
) {
    val items = content.items
    val anchorRow = remember { FocusRequester() }
    val lastRow = remember { FocusRequester() }
    val pagingHeldFocus = remember { mutableStateOf(false) }
    val handOffToLastRow = remember { mutableStateOf(false) }
    val pagingShown = content.nextCursor != null || content.isLoadingMore || content.pageFailure != null
    if (!pagingShown && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    val listState = rememberLazyListState()
    val anchorIndex by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(listState) {
        val anchorKey = items.getOrNull(anchorIndex)?.id?.value ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == anchorKey } }.first { it }
        withFrameNanos {}
        if (paneHasFocus.value) listFocus.requestFocus()
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val lastId = items.last().id.value
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == lastId }) listState.scrollToItem(items.lastIndex)
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == lastId } }.first { it }
        withFrameNanos {}
        if (owner.owns(listFocus)) lastRow.requestFocus()
        handOffToLastRow.value = false
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .then(owner.section(listFocus))
            .focusRequester(listFocus)
            .focusRestorer {
                val lastComposed = listState.layoutInfo.visibleItemsInfo.any { it.key == items.last().id.value }
                if (handOffToLastRow.value && lastComposed) lastRow else anchorRow
            }
            .focusGroup()
            .testTag(TV_TRASH_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id.value }) { index, item ->
            TvTrashRow(
                item = item,
                onClick = { onChoose(item) },
                modifier = Modifier
                    .then(if (index == anchorIndex) Modifier.focusRequester(anchorRow) else Modifier)
                    .then(if (index == items.lastIndex) Modifier.focusRequester(lastRow) else Modifier),
            )
        }
        if (pagingShown) {
            item(key = TV_TRASH_PAGING_KEY) {
                TvTrashPaging(
                    content = content,
                    onNextPage = onNextPage,
                    onRetry = onRetry,
                    buttonModifier = Modifier.onFocusChanged { pagingHeldFocus.value = it.isFocused },
                )
            }
        }
    }
}

@Composable
private fun TvTrashRow(
    item: TrashItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val metadata = remember(context, item) {
        val size = Formatter.formatShortFileSize(context, item.sizeBytes.coerceAtLeast(0L))
        val deleted = item.deletedAt?.let { trashDate(context, it) } ?: "–"
        val expires = item.expirationDate?.let { trashDate(context, it) } ?: "–"
        context.getString(R.string.tv_trash_row_metadata, size, deleted, expires)
    }
    val label = stringResource(R.string.tv_trash_row_actions, item.name)
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(metadata, maxLines = 1) },
        leadingContent = {
            Icon(
                painter = painterResource(fileTypeIconRes(item.type)),
                contentDescription = null,
                tint = PutioDesignTokens.yellowSolid,
                modifier = Modifier.size(ListItemDefaults.IconSize),
            )
        },
        scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .testTag(TV_TRASH_ROW_TAG),
    )
}

/** A date for the row; the raw stamp when it cannot be parsed, never a fabricated deadline. */
private fun trashDate(context: android.content.Context, value: String): String {
    val millis = parseTrashTimestamp(value)?.toEpochMilli() ?: return value
    return DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
    )
}

@Composable
private fun TvTrashPaging(
    content: TrashContent.Loaded,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    buttonModifier: Modifier = Modifier,
) {
    val failure = content.pageFailure
    val label = when {
        content.isLoadingMore -> R.string.tv_trash_loading_more
        failure != null -> R.string.tv_files_retry
        else -> R.string.tv_trash_load_more
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failure != null) {
            Text(stringResource(R.string.tv_trash_paging_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TvButton(
            onClick = {
                when {
                    content.isLoadingMore -> Unit
                    failure != null -> onRetry()
                    else -> onNextPage()
                }
            },
            modifier = buttonModifier,
        ) {
            Text(stringResource(label))
        }
    }
}

/** Full-width rows scale less than compact surfaces so they stay inside the safe area. */
private const val FULL_WIDTH_FOCUSED_SCALE = 1.02f
private const val TV_TRASH_PAGING_KEY = "tv-trash-paging"
