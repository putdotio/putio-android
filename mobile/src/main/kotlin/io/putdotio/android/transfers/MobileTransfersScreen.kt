package io.putdotio.android.transfers

import android.content.ClipData
import android.os.PersistableBundle
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileErrorState
import io.putdotio.android.MobileLoadingState
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioDestructiveButton
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.mobileMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val MOBILE_TRANSFERS_LIST_TAG = "mobile-transfers-list"
internal const val MOBILE_TRANSFERS_SNACKBAR_TAG = "mobile-transfers-snackbar"
internal const val MOBILE_TRANSFERS_ADD_FAB_TAG = "mobile-transfers-add-fab"
internal const val MOBILE_TRANSFERS_OVERFLOW_TAG = "mobile-transfers-overflow"

/**
 * Transfers: sections of transfer cards (Active, Needs attention, Completed), a yellow extended FAB
 * that opens Add transfer, and Transfer actions for one card. The tab title and its overflow belong
 * to the shell ([MobileTransfersOverflow]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileTransfersScreen(
    state: TransfersState,
    onEvent: (TransfersEvent) -> Unit,
    modifier: Modifier = Modifier,
    sessionId: MobileAuthSessionId? = null,
    draft: MobileTransferDraft = remember { MobileTransferDraft() },
    filesRepository: FilesRepository? = null,
    onFilesAuthenticationRequired: suspend () -> Unit = {},
) {
    LaunchedEffect(draft, sessionId, state.mutation, state.lastSuccessfulAddRequestId) {
        draft.reconcileSession(sessionId)
        draft.reconcileTransfers(state)
    }
    var stopping by remember(sessionId) { mutableStateOf<TransferItem?>(null) }
    var actionsFor by remember(sessionId) { mutableStateOf<TransferId?>(null) }
    val snackbarHostState = remember(sessionId) { SnackbarHostState() }
    MobileTransfersNews(state, snackbarHostState, sessionId, onEvent)

    val enabled = state.enabledInteractions()
    val listState = rememberLazyListState()
    val showFab = state.content !is TransfersContent.Empty
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MobileTransfersRefreshFailure(state.refresh, enabled.controls, onEvent)
            PullToRefreshBox(
                isRefreshing = state.refresh is TransfersRefresh.Refreshing,
                onRefresh = { if (enabled.refresh) onEvent(TransfersEvent.Refresh) },
                modifier = Modifier.weight(1f),
            ) {
                MobileTransfersContent(
                    state = state,
                    listState = listState,
                    enabled = enabled,
                    onEvent = onEvent,
                    onActions = { actionsFor = it },
                    onAdd = draft::open,
                )
            }
        }
        if (showFab) {
            val expanded by remember {
                derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            }
            MobileAddTransferFab(
                expanded = expanded,
                onClick = draft::open,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showFab) FAB_CLEARANCE else 0.dp)
                .testTag(MOBILE_TRANSFERS_SNACKBAR_TAG),
        )
    }

    MobileAddTransfer(
        draft = draft,
        state = state,
        onEvent = onEvent,
        enabled = enabled.controls,
        filesRepository = filesRepository,
        onFilesAuthenticationRequired = onFilesAuthenticationRequired,
    )
    // Outside the sheet: the copy outlives the sheet it closes.
    val copy = rememberSourceCopy(snackbarHostState)
    val actionsItem = actionsFor?.let { id -> (state.content as? TransfersContent.Ready)?.items?.find { it.id == id } }
    if (actionsItem != null) {
        MobileTransferActionsSheet(
            item = actionsItem,
            enabled = enabled.rows,
            folders = filesRepository,
            actions = MobileTransferActions(
                onOpenFile = { onEvent(TransfersEvent.Open(actionsItem.id)) },
                onOpenFolder = { onEvent(TransfersEvent.OpenFolder(actionsItem.id)) },
                onCopySource = { actionsItem.source?.let(copy) },
                onStop = { stopping = actionsItem },
                onRetry = { onEvent(TransfersEvent.RetryTransfer(actionsItem.id)) },
                onRemove = { onEvent(TransfersEvent.Remove(actionsItem.id)) },
            ),
            onDismiss = { actionsFor = null },
            onAuthenticationRequired = onFilesAuthenticationRequired,
        )
    }
    stopping?.let { item ->
        MobileTransferConfirmation(
            title = R.string.mobile_transfers_cancel_title,
            message = stringResource(R.string.mobile_transfers_cancel_message, item.name),
            confirm = R.string.mobile_transfers_cancel_confirm,
            onDismiss = { stopping = null },
            onConfirm = {
                stopping = null
                onEvent(TransfersEvent.Cancel(item.id))
            },
        )
    }
    MobileTransferActionFailure(state.mutation, onEvent)
}

/** Started transfers and retry results, each once, replacing a visible snackbar instead of queueing. */
@Composable
private fun MobileTransfersNews(
    state: TransfersState,
    snackbarHostState: SnackbarHostState,
    sessionId: MobileAuthSessionId?,
    onEvent: (TransfersEvent) -> Unit,
) {
    val addedCount = state.lastAddReceipt?.addedCount ?: 0
    val addedMessage = pluralStringResource(R.plurals.mobile_transfers_added, addedCount, addedCount)
    // The successful add id changes once per accepted submission; the first value is history, not news.
    var announcedAdd by remember(sessionId) { mutableStateOf(state.lastSuccessfulAddRequestId) }
    LaunchedEffect(state.lastSuccessfulAddRequestId) {
        val added = state.lastSuccessfulAddRequestId
        if (added != null && added != announcedAdd) {
            announcedAdd = added
            // Refused links reopen the draft with their own message; only started transfers are news.
            if (addedCount > 0) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(addedMessage)
            }
        }
    }
    val retryMessage = state.retryOutcome?.let { retryOutcomeMessage(it) }
    LaunchedEffect(state.retryOutcome) {
        val outcome = state.retryOutcome ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        try {
            snackbarHostState.showSnackbar(requireNotNull(retryMessage))
        } finally {
            onEvent(TransfersEvent.DismissRetryOutcome(outcome.requestId))
        }
    }
}

@Composable
private fun rememberSourceCopy(snackbarHostState: SnackbarHostState): (TransferSource) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.mobile_transfer_source_clip_label)
    val copied = stringResource(R.string.mobile_transfer_link_copied)
    return { source ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(sensitiveClip(label, source.value)))
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(copied)
        }
    }
}

/**
 * A source can carry a tracker passkey or a signed URL, so the clipboard keeps it out of previews.
 * Android documents the literal key for releases before `ClipDescription.EXTRA_IS_SENSITIVE` existed.
 */
private fun sensitiveClip(label: String, link: String): ClipData =
    ClipData.newPlainText(label, link).apply {
        description.extras = PersistableBundle().apply { putBoolean(CLIP_IS_SENSITIVE, true) }
    }

private const val CLIP_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/** Material clears the label's semantics so a collapsed FAB keeps one name; the FAB carries it instead. */
@Composable
private fun MobileAddTransferFab(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.mobile_transfers_add)
    ExtendedFloatingActionButton(
        text = { Text(label, style = PutioTypeScale.bodyLg.copy(fontWeight = FontWeight.Medium)) },
        icon = { Icon(painterResource(R.drawable.ic_ph_plus), contentDescription = null) },
        onClick = onClick,
        expanded = expanded,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .semantics { contentDescription = label }
            .testTag(MOBILE_TRANSFERS_ADD_FAB_TAG),
    )
}

/**
 * The Transfers tab's overflow in the tab-title row: Refresh once the list has loaded, empty included,
 * so keyboard and TalkBack users need no pull; and Clear completed, which confirms, while transfers are
 * listed.
 */
@Composable
internal fun MobileTransfersOverflow(
    state: TransfersState,
    onEvent: (TransfersEvent) -> Unit,
    sessionId: MobileAuthSessionId?,
) {
    val listed = state.content is TransfersContent.Ready
    if (!listed && state.content !is TransfersContent.Empty) return
    val enabled = state.enabledInteractions()
    var expanded by remember(sessionId) { mutableStateOf(false) }
    var confirming by remember(sessionId) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(MOBILE_TRANSFERS_OVERFLOW_TAG)) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_dots_three_vertical),
                contentDescription = stringResource(R.string.mobile_transfers_more),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_transfers_refresh)) },
                enabled = enabled.refresh,
                onClick = {
                    expanded = false
                    onEvent(TransfersEvent.Refresh)
                },
            )
            if (listed) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mobile_transfers_clean)) },
                    enabled = enabled.controls,
                    onClick = {
                        expanded = false
                        confirming = true
                    },
                )
            }
        }
    }
    if (confirming) {
        MobileTransferConfirmation(
            title = R.string.mobile_transfers_clean_title,
            message = stringResource(R.string.mobile_transfers_clean_message),
            confirm = R.string.mobile_transfers_clean_confirm,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onEvent(TransfersEvent.CleanCompleted)
            },
        )
    }
}

@Composable
private fun MobileTransfersContent(
    state: TransfersState,
    listState: LazyListState,
    enabled: TransfersInteractions,
    onEvent: (TransfersEvent) -> Unit,
    onActions: (TransferId) -> Unit,
    onAdd: () -> Unit,
) {
    when (val content = state.content) {
        is TransfersContent.InitialLoading ->
            MobileLoadingState(message = stringResource(R.string.mobile_transfers_loading))
        TransfersContent.Empty ->
            MobileEmptyState(
                title = stringResource(R.string.mobile_transfers_empty_title),
                message = stringResource(R.string.mobile_transfers_empty_message),
                // The state sits a little above the middle, clear of the navigation.
                modifier = Modifier.padding(bottom = 56.dp),
                glyph = PutioEmptyGlyph.NoTransfers,
            ) {
                Button(onClick = onAdd, enabled = enabled.controls) {
                    Icon(
                        painterResource(R.drawable.ic_ph_plus),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.mobile_transfers_add), modifier = Modifier.padding(start = 8.dp))
                }
            }
        is TransfersContent.Failed ->
            MobileErrorState(
                title = stringResource(R.string.mobile_transfers_error_title),
                message = content.failure.mobileMessage(),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = { onEvent(TransfersEvent.RetryLoad) },
                retryEnabled = enabled.rows,
            )
        is TransfersContent.Ready ->
            MobileTransfersList(content, state.mutation, listState, enabled, onEvent, onActions)
    }
}

@Composable
private fun MobileTransfersList(
    content: TransfersContent.Ready,
    mutation: TransferMutation,
    listState: LazyListState,
    enabled: TransfersInteractions,
    onEvent: (TransfersEvent) -> Unit,
    onActions: (TransferId) -> Unit,
) {
    val nowMillis by produceMinuteClock()
    val sections = remember(content.items) { content.items.groupBy(TransferItem::section) }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(MOBILE_TRANSFERS_LIST_TAG),
        contentPadding = PaddingValues(top = 8.dp, bottom = FAB_CLEARANCE),
    ) {
        for (section in MobileTransferSection.entries) {
            val rows = sections[section] ?: continue
            item(key = "section-${section.name}", contentType = "section") {
                MobileTransferSectionLabel(section.label)
            }
            items(rows, key = { it.id.value }, contentType = { "card" }) { item ->
                MobileTransferCard(
                    item = item,
                    busy = mutation is TransferMutation.Running && mutation.action.targets(item.id),
                    enabled = enabled.rows,
                    nowMillis = nowMillis,
                    onOpen = { onEvent(TransfersEvent.Open(item.id)) },
                    onActions = { onActions(item.id) },
                    onRetry = { onEvent(TransfersEvent.RetryTransfer(item.id)) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (content.paging != TransfersPaging.Complete) {
            item(key = "transfers-paging") {
                MobileTransfersPaging(content.paging, enabled.paging, onEvent)
            }
        }
    }
}

/** The time completed cards measure "3 hr. ago" from, moved on once a minute while shown. */
@Composable
private fun produceMinuteClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(MINUTE_MILLIS)
        value = System.currentTimeMillis()
    }
}

@Composable
private fun MobileTransferSectionLabel(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun MobileTransfersPaging(
    paging: TransfersPaging,
    interactionsEnabled: Boolean,
    onEvent: (TransfersEvent) -> Unit,
) {
    when (paging) {
        is TransfersPaging.Available ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    colors = neutralTextButtonColors(),
                    onClick = { onEvent(TransfersEvent.LoadNextPage) },
                    enabled = interactionsEnabled,
                ) {
                    Text(stringResource(R.string.mobile_transfers_load_more))
                }
            }
        is TransfersPaging.Loading -> {
            val message = stringResource(R.string.mobile_transfers_loading_more)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = message },
                    strokeWidth = 2.dp,
                )
                Text(message)
            }
        }
        is TransfersPaging.Failed ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.mobile_transfers_paging_error))
                TextButton(
                    colors = neutralTextButtonColors(),
                    onClick = { onEvent(TransfersEvent.RetryLoad) },
                    enabled = interactionsEnabled,
                ) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }
        TransfersPaging.Complete -> Unit
    }
}

@Composable
private fun MobileTransfersRefreshFailure(
    refresh: TransfersRefresh,
    interactionsEnabled: Boolean,
    onEvent: (TransfersEvent) -> Unit,
) {
    if (refresh is TransfersRefresh.Failed) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.mobile_transfers_refresh_error),
                modifier = Modifier.weight(1f),
                color = PutioDesignTokens.redTextSecondary,
            )
            TextButton(
                colors = neutralTextButtonColors(),
                onClick = { onEvent(TransfersEvent.Refresh) },
                enabled = interactionsEnabled,
            ) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        }
    }
}

@Composable
private fun MobileTransferActionFailure(mutation: TransferMutation, onEvent: (TransfersEvent) -> Unit) {
    val failure = (mutation as? TransferMutation.Failed)?.takeUnless { it.action is TransferAction.Add } ?: return
    AlertDialog(
        onDismissRequest = { onEvent(TransfersEvent.DismissMutationFailure) },
        title = { Text(stringResource(R.string.mobile_transfers_action_error_title)) },
        text = { Text(failure.failure.mobileMessage()) },
        confirmButton = {
            TextButton(onClick = { onEvent(TransfersEvent.DismissMutationFailure) }) {
                Text(stringResource(R.string.mobile_action_ok))
            }
        },
    )
}

@Composable
private fun MobileTransferConfirmation(
    @StringRes title: Int,
    message: String,
    @StringRes confirm: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(message) },
        confirmButton = { PutioDestructiveButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = neutralTextButtonColors()) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}

@Composable
private fun retryOutcomeMessage(outcome: TransferRetryOutcome): String =
    when (outcome) {
        is TransferRetryOutcome.Accepted -> stringResource(R.string.mobile_transfers_retry_accepted)
        is TransferRetryOutcome.Failed ->
            stringResource(
                R.string.mobile_transfers_retry_failed,
                // put.io answers 403 when the transfer has no error left to retry.
                if (outcome.failure is PutioFailure.AccessDenied) {
                    stringResource(R.string.mobile_transfers_retry_not_failed)
                } else {
                    outcome.failure.mobileMessage()
                },
            )
    }

/** The status a card shows; the home-screen widget shows the same words. */
@StringRes
internal fun TransferItem.statusLabelRes(): Int =
    when (status) {
        AppTransferStatus.Waiting -> R.string.mobile_transfer_status_waiting
        AppTransferStatus.PreparingDownload -> R.string.mobile_transfer_status_preparing
        AppTransferStatus.Queued -> R.string.mobile_transfer_status_queued
        AppTransferStatus.Downloading -> R.string.mobile_transfer_status_downloading
        AppTransferStatus.WaitingForCompleteQueue,
        AppTransferStatus.Completing,
        -> R.string.mobile_transfer_status_finishing
        AppTransferStatus.WaitingForDownloader -> R.string.mobile_transfer_status_waiting_for_downloader
        AppTransferStatus.Stopping -> R.string.mobile_transfer_status_stopping
        AppTransferStatus.Seeding -> R.string.mobile_transfer_status_seeding
        AppTransferStatus.PreparingSeed -> R.string.mobile_transfer_status_preparing_seed
        AppTransferStatus.Completed -> completedStatusResource()
        AppTransferStatus.Failed -> R.string.mobile_transfer_status_failed
        is AppTransferStatus.Unknown -> R.string.mobile_transfer_status_updating
    }

private fun TransferItem.completedStatusResource(): Int =
    when {
        userFileExists == false -> R.string.mobile_transfer_file_unavailable
        fileId == null -> R.string.mobile_transfer_file_preparing
        else -> R.string.mobile_transfer_status_completed
    }

internal data class TransfersInteractions(
    val controls: Boolean,
    val rows: Boolean,
    val paging: Boolean,
    val refresh: Boolean,
)

internal fun TransfersState.enabledInteractions(): TransfersInteractions {
    val controls =
        mutation !is TransferMutation.Running &&
            navigation !is TransferNavigation.Resolving &&
            content !is TransfersContent.InitialLoading
    val rows = controls && refresh !is TransfersRefresh.Refreshing && !content.isPaging
    return TransfersInteractions(
        controls = controls,
        rows = rows,
        paging = rows && refresh !is TransfersRefresh.Polling,
        refresh = controls &&
            (content is TransfersContent.Empty || content is TransfersContent.Ready) &&
            !refresh.isRunning &&
            !content.isPaging,
    )
}

private fun TransferAction.targets(id: TransferId): Boolean =
    when (this) {
        is TransferAction.Cancel -> this.id == id
        is TransferAction.Retry -> this.id == id
        is TransferAction.Remove -> this.id == id
        TransferAction.Clean -> false
        is TransferAction.Add -> false
    }

/** Room the FAB takes above the list's end and the snackbar: 56dp and its 16dp margins. */
private val FAB_CLEARANCE = 88.dp
private const val MINUTE_MILLIS = 60_000L
