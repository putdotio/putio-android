package io.putdotio.android

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferAction
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersRefresh
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.transfers.canCancel
import io.putdotio.android.transfers.canOpen
import io.putdotio.android.transfers.isPaging
import io.putdotio.android.transfers.isRunning
import java.text.NumberFormat

internal const val MOBILE_TRANSFERS_LIST_TAG = "mobile-transfers-list"
internal const val MOBILE_TRANSFER_ADD_FIELD_TAG = "mobile-transfer-add-field"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileTransfersScreen(
    state: TransfersState,
    onEvent: (TransfersEvent) -> Unit,
    modifier: Modifier = Modifier,
    sessionId: MobileAuthSessionId? = null,
    draft: MobileTransferDraft = remember { MobileTransferDraft() },
) {
    LaunchedEffect(draft, sessionId, state.mutation, state.lastSuccessfulAddRequestId) {
        draft.reconcileSession(sessionId)
        draft.reconcileTransfers(state)
    }
    var confirmation by remember(sessionId) { mutableStateOf<TransferConfirmation?>(null) }

    val controlsEnabled =
        state.mutation !is TransferMutation.Running &&
            state.navigation !is TransferNavigation.Resolving &&
            state.content !is TransfersContent.InitialLoading
    val rowInteractionsEnabled =
        controlsEnabled &&
            state.refresh !is TransfersRefresh.Refreshing &&
            !state.content.isPaging
    val pagingInteractionsEnabled =
        rowInteractionsEnabled && state.refresh !is TransfersRefresh.Polling
    val refreshEnabled =
        controlsEnabled &&
            (state.content is TransfersContent.Empty || state.content is TransfersContent.Ready) &&
            !state.refresh.isRunning &&
            !state.content.isPaging
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                MobileAddTransfer(
                    draft = draft,
                    state = state,
                    onEvent = onEvent,
                    enabled = controlsEnabled,
                )
            }
            MobileTransferActionsMenu(
                refreshEnabled = refreshEnabled,
                cleanEnabled = controlsEnabled,
                showClean = state.content is TransfersContent.Ready,
                sessionId = sessionId,
                onRefresh = { onEvent(TransfersEvent.Refresh) },
                onClean = { confirmation = TransferConfirmation.Clean },
            )
        }

        MobileTransfersRefreshFailure(state.refresh, controlsEnabled, onEvent)
        PullToRefreshBox(
            isRefreshing = state.refresh is TransfersRefresh.Refreshing,
            onRefresh = { if (refreshEnabled) onEvent(TransfersEvent.Refresh) },
            modifier = Modifier.weight(1f),
        ) {
            MobileTransfersContent(
                content = state.content,
                mutation = state.mutation,
                interactionsEnabled = rowInteractionsEnabled,
                pagingEnabled = pagingInteractionsEnabled,
                onEvent = onEvent,
                onConfirmation = { confirmation = it },
            )
        }
    }

    confirmation?.let { pending ->
        MobileTransferConfirmation(
            confirmation = pending,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                onEvent(pending.event)
            },
        )
    }

    val nonAddFailure =
        (state.mutation as? TransferMutation.Failed)?.takeUnless { it.action is TransferAction.Add }
    if (nonAddFailure != null) {
        AlertDialog(
            onDismissRequest = { onEvent(TransfersEvent.DismissMutationFailure) },
            title = { Text(stringResource(R.string.mobile_transfers_action_error_title)) },
            text = { Text(stringResource(nonAddFailure.failure.mobileMessageResource())) },
            confirmButton = {
                TextButton(onClick = { onEvent(TransfersEvent.DismissMutationFailure) }) {
                    Text(stringResource(R.string.mobile_action_ok))
                }
            },
        )
    }
}

@Composable
private fun MobileTransferActionsMenu(
    refreshEnabled: Boolean,
    cleanEnabled: Boolean,
    showClean: Boolean,
    sessionId: MobileAuthSessionId?,
    onRefresh: () -> Unit,
    onClean: () -> Unit,
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_dots_three_vertical),
                contentDescription = stringResource(R.string.mobile_transfer_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_transfers_refresh)) },
                enabled = refreshEnabled,
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
            if (showClean) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mobile_transfers_clean)) },
                    enabled = cleanEnabled,
                    onClick = {
                        expanded = false
                        onClean()
                    },
                )
            }
        }
    }
}

@Composable
private fun MobileTransfersContent(
    content: TransfersContent,
    mutation: TransferMutation,
    interactionsEnabled: Boolean,
    pagingEnabled: Boolean,
    onEvent: (TransfersEvent) -> Unit,
    onConfirmation: (TransferConfirmation) -> Unit,
) {
    when (content) {
        is TransfersContent.InitialLoading ->
            MobileLoadingState(message = stringResource(R.string.mobile_transfers_loading))
        TransfersContent.Empty ->
            MobileEmptyState(
                title = stringResource(R.string.mobile_transfers_empty_title),
                message = stringResource(R.string.mobile_transfers_empty_message),
            )
        is TransfersContent.Failed ->
            MobileErrorState(
                title = stringResource(R.string.mobile_transfers_error_title),
                message = stringResource(content.failure.mobileMessageResource()),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = { onEvent(TransfersEvent.RetryLoad) },
                retryEnabled = interactionsEnabled,
            )
        is TransfersContent.Ready ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(MOBILE_TRANSFERS_LIST_TAG),
            ) {
                items(content.items, key = { it.id.value }) { item ->
                    MobileTransferRow(
                        item = item,
                        mutation = mutation,
                        interactionsEnabled = interactionsEnabled,
                        onEvent = onEvent,
                        onConfirmation = onConfirmation,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
                if (content.paging != TransfersPaging.Complete) {
                    item(key = "transfers-paging") {
                        MobileTransfersPaging(content.paging, pagingEnabled, onEvent)
                    }
                }
            }
    }
}

@Composable
private fun MobileTransferRow(
    item: TransferItem,
    mutation: TransferMutation,
    interactionsEnabled: Boolean,
    onEvent: (TransfersEvent) -> Unit,
    onConfirmation: (TransferConfirmation) -> Unit,
) {
    val context = LocalContext.current
    val busy = mutation is TransferMutation.Running && mutation.action.targets(item.id)
    val actionsEnabled = interactionsEnabled && mutation !is TransferMutation.Running
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackActions = maxWidth / LocalDensity.current.fontScale < 320.dp
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MobileTransferDetails(item, context)
                    if (stackActions) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MobileTransferRowActions(item, busy, actionsEnabled, onEvent, onConfirmation)
                        }
                    }
                }
            },
            trailingContent = if (stackActions) null else {
                {
                    Column(horizontalAlignment = Alignment.End) {
                        MobileTransferRowActions(item, busy, actionsEnabled, onEvent, onConfirmation)
                    }
                }
            },
        )
    }
}

@Composable
private fun MobileTransferDetails(item: TransferItem, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.statusLabel())
        item.percentDone?.takeIf { it in 0.0..PERCENTAGE_SCALE }?.let { percent ->
            LinearProgressIndicator(
                progress = { (percent / PERCENTAGE_SCALE).toFloat() },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            )
            Text(
                text = NumberFormat.getPercentInstance().format(percent / PERCENTAGE_SCALE),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (item.hasError) {
            Text(
                text = stringResource(R.string.mobile_transfer_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item.details(context).takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MobileTransferRowActions(
    item: TransferItem,
    busy: Boolean,
    actionsEnabled: Boolean,
    onEvent: (TransfersEvent) -> Unit,
    onConfirmation: (TransferConfirmation) -> Unit,
) {
    if (item.canOpen) {
        val openLabel = stringResource(R.string.mobile_transfers_open_named, item.name)
        TextButton(
            onClick = { onEvent(TransfersEvent.Open(item.id)) },
            enabled = actionsEnabled,
            modifier = Modifier.semantics { contentDescription = openLabel },
        ) {
            Text(stringResource(R.string.mobile_transfers_open), modifier = Modifier.clearAndSetSemantics {})
        }
    }
    when (item.status) {
        AppTransferStatus.Failed -> {
            val retryLabel = stringResource(R.string.mobile_transfers_retry_named, item.name)
            TextButton(
                onClick = { onConfirmation(TransferConfirmation.Retry(item.id, item.name)) },
                enabled = actionsEnabled,
                modifier = Modifier.semantics { contentDescription = retryLabel },
            ) {
                Text(stringResource(R.string.mobile_action_retry), modifier = Modifier.clearAndSetSemantics {})
            }
        }
        else ->
            if (item.status.canCancel) {
                val cancelLabel = stringResource(R.string.mobile_transfers_cancel_named, item.name)
                TextButton(
                    onClick = { onConfirmation(TransferConfirmation.Cancel(item.id, item.name)) },
                    enabled = actionsEnabled,
                    modifier = Modifier.semantics { contentDescription = cancelLabel },
                ) {
                    Text(stringResource(R.string.mobile_action_cancel), modifier = Modifier.clearAndSetSemantics {})
                }
            }
    }
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
    }
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
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = { onEvent(TransfersEvent.Refresh) },
                enabled = interactionsEnabled,
            ) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        }
    }
}

@Composable
private fun MobileAddTransfer(
    draft: MobileTransferDraft,
    state: TransfersState,
    onEvent: (TransfersEvent) -> Unit,
    enabled: Boolean,
) {
    val input by draft.state.collectAsStateWithLifecycle()
    val adding = (state.mutation as? TransferMutation.Running)?.action is TransferAction.Add
    Button(onClick = draft::open, enabled = enabled) {
        Text(stringResource(R.string.mobile_transfers_add))
    }
    if (input.open) {
        val addFailure = (state.mutation as? TransferMutation.Failed)?.takeIf { it.action is TransferAction.Add }
        MobileAddTransferSheet(
            input = input.input,
            validation = input.validation,
            failure = addFailure,
            adding = adding,
            submitEnabled = enabled && input.validation != MobileShareValidation.TooLong,
            onInputChanged = {
                draft.edit(it)
                if (addFailure != null) onEvent(TransfersEvent.DismissMutationFailure)
            },
            onDismiss = {
                if (!adding) {
                    draft.dismiss()
                    if (addFailure != null) onEvent(TransfersEvent.DismissMutationFailure)
                }
            },
            onSubmit = {
                if (enabled) draft.validate()?.let { normalized ->
                    if (addFailure != null) onEvent(TransfersEvent.DismissMutationFailure)
                    onEvent(TransfersEvent.Add(normalized))
                }
            },
        )
    }
    if (input.pendingReplacement) {
        val mutationRunning = state.mutation is TransferMutation.Running
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        AlertDialog(
            modifier = if (landscape) {
                Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth()
            } else {
                Modifier
            },
            properties = DialogProperties(usePlatformDefaultWidth = !landscape),
            onDismissRequest = { if (!mutationRunning) draft.keepDraft() },
            title = { Text(stringResource(R.string.mobile_share_replace_title)) },
            text = {
                Text(
                    stringResource(R.string.mobile_share_replace_message),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    draft.useSharedLink()
                    val failedAction = (state.mutation as? TransferMutation.Failed)?.action
                    if (failedAction is TransferAction.Add) onEvent(TransfersEvent.DismissMutationFailure)
                }, enabled = !mutationRunning) {
                    Text(stringResource(R.string.mobile_share_use_link))
                }
            },
            dismissButton = {
                TextButton(onClick = draft::keepDraft, enabled = !mutationRunning) {
                    Text(stringResource(R.string.mobile_share_keep_draft))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileAddTransferSheet(
    input: String,
    validation: MobileShareValidation?,
    failure: TransferMutation.Failed?,
    adding: Boolean,
    submitEnabled: Boolean,
    onInputChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.mobile_transfers_add_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MOBILE_TRANSFER_ADD_FIELD_TAG),
                enabled = !adding,
                isError = validation != null || failure != null,
                label = { Text(stringResource(R.string.mobile_transfers_add_label)) },
                placeholder = { Text(stringResource(R.string.mobile_transfers_add_placeholder)) },
                supportingText = {
                    when {
                        validation != null -> Text(stringResource(when (validation) {
                            MobileShareValidation.InvalidLink -> R.string.mobile_transfers_add_invalid
                            MobileShareValidation.MultipleLinks -> R.string.mobile_share_multiple_links
                            MobileShareValidation.TooLong -> R.string.mobile_share_too_long
                        }))
                        failure != null -> Text(stringResource(failure.failure.mobileMessageResource()))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (submitEnabled) onSubmit() }),
                minLines = 2,
                maxLines = 4,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !adding) {
                    Text(stringResource(R.string.mobile_action_cancel))
                }
                val addingDescription = stringResource(R.string.mobile_transfers_adding)
                Button(
                    onClick = onSubmit,
                    enabled = submitEnabled,
                    modifier =
                        Modifier.semantics {
                            if (adding) {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = addingDescription
                            }
                        },
                ) {
                    if (adding) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = stringResource(R.string.mobile_transfers_add_confirm),
                        modifier = Modifier.padding(start = if (adding) 8.dp else 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileTransferConfirmation(
    confirmation: TransferConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title =
        when (confirmation) {
            is TransferConfirmation.Cancel -> stringResource(R.string.mobile_transfers_cancel_title)
            is TransferConfirmation.Retry -> stringResource(R.string.mobile_transfers_retry_title)
            is TransferConfirmation.Clean -> stringResource(R.string.mobile_transfers_clean_title)
        }
    val message =
        when (confirmation) {
            is TransferConfirmation.Cancel ->
                stringResource(R.string.mobile_transfers_cancel_message, confirmation.name)
            is TransferConfirmation.Retry -> stringResource(R.string.mobile_transfers_retry_message, confirmation.name)
            is TransferConfirmation.Clean -> stringResource(R.string.mobile_transfers_clean_message)
        }
    val action =
        when (confirmation) {
            is TransferConfirmation.Cancel -> stringResource(R.string.mobile_transfers_cancel_confirm)
            is TransferConfirmation.Retry -> stringResource(R.string.mobile_action_retry)
            is TransferConfirmation.Clean -> stringResource(R.string.mobile_transfers_clean_confirm)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_action_cancel)) }
        },
    )
}

@Composable
private fun TransferItem.statusLabel(): String =
    stringResource(
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
            AppTransferStatus.Completed ->
                when {
                    userFileExists == false -> R.string.mobile_transfer_file_unavailable
                    fileId == null -> R.string.mobile_transfer_file_preparing
                    else -> R.string.mobile_transfer_status_completed
                }
            AppTransferStatus.Failed -> R.string.mobile_transfer_status_failed
            is AppTransferStatus.Unknown -> R.string.mobile_transfer_status_updating
        },
    )

private fun TransferItem.details(context: Context): String =
    buildList {
        sizeBytes?.takeIf { it >= 0.0 }?.let {
            add(Formatter.formatShortFileSize(context, it.toLong()))
        }
        downloadSpeedBytesPerSecond?.takeIf { it > 0.0 }?.let {
            add(
                context.getString(
                    R.string.mobile_transfer_download_speed,
                    Formatter.formatShortFileSize(context, it.toLong()),
                ),
            )
        }
        uploadSpeedBytesPerSecond?.takeIf { it > 0.0 }?.let {
            add(
                context.getString(
                    R.string.mobile_transfer_upload_speed,
                    Formatter.formatShortFileSize(context, it.toLong()),
                ),
            )
        }
        estimatedSecondsRemaining?.takeIf { it > 0.0 }?.let {
            add(context.getString(R.string.mobile_transfer_eta, DateUtils.formatElapsedTime(it.toLong())))
        }
        availability?.takeIf { it in 0.0..PERCENTAGE_SCALE }?.let {
            val percentage = NumberFormat.getPercentInstance().format(it / PERCENTAGE_SCALE)
            add(context.getString(R.string.mobile_transfer_availability, percentage))
        }
    }.joinToString(" · ")

private fun TransferAction.targets(id: TransferId): Boolean =
    when (this) {
        is TransferAction.Cancel -> this.id == id
        is TransferAction.Retry -> this.id == id
        TransferAction.Clean -> false
        is TransferAction.Add -> false
    }

private fun TransfersContent.items(): List<TransferItem> =
    when (this) {
        is TransfersContent.Ready -> items
        TransfersContent.Empty,
        is TransfersContent.Failed,
        is TransfersContent.InitialLoading,
        -> emptyList()
    }

private sealed interface TransferConfirmation {
    val event: TransfersEvent

    data class Cancel(val id: TransferId, val name: String) : TransferConfirmation {
        override val event = TransfersEvent.Cancel(id)
    }

    data class Retry(val id: TransferId, val name: String) : TransferConfirmation {
        override val event = TransfersEvent.RetryTransfer(id)
    }

    data object Clean : TransferConfirmation {
        override val event = TransfersEvent.CleanCompleted
    }
}

private const val PERCENTAGE_SCALE = 100.0
