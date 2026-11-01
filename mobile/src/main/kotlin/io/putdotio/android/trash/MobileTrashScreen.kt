package io.putdotio.android.trash

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileDialogDismissButton
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileLoadingState
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobileRow
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.mobileMessage
import java.time.LocalDate

internal const val MOBILE_TRASH_ROUTE = "account/trash"
internal const val MOBILE_MANAGE_TRASH_TAG = "mobile-manage-trash"
internal const val MOBILE_TRASH_LIST_TAG = "mobile-trash-list"
internal const val MOBILE_TRASH_CONFIRM_TAG = "mobile-trash-confirm"
internal const val MOBILE_TRASH_CHECK_TAG = "mobile-trash-check"
internal const val MOBILE_TRASH_OUTCOME_TAG = "mobile-trash-outcome"

@Composable
internal fun MobileTrashScreen(
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var sheetItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    val sheetItem = (state.content as? TrashContent.Loaded)?.items?.firstOrNull { it.id.value == sheetItemId }
    val listState = rememberLazyListState()
    // A new outcome is inserted above the list content while the list stays anchored on its
    // previous first item, which leaves the outcome and its recovery controls above the viewport.
    LaunchedEffect(state.restoreOutcome?.item) {
        if (state.restoreOutcome != null) listState.scrollToItem(0)
    }
    LaunchedEffect(state.actionOutcome?.action) {
        if (state.actionOutcome != null) listState.scrollToItem(if (state.restoreOutcome != null) 1 else 0)
    }
    LazyColumn(modifier.fillMaxSize().testTag(MOBILE_TRASH_LIST_TAG), state = listState) {
        state.restoreOutcome?.let { outcome ->
            item(key = "restore-outcome") {
                MobileTrashOutcome(outcome, state.authenticationFailure == null, onEvent)
            }
        }
        state.actionOutcome?.let { outcome ->
            item(key = "action-outcome") {
                MobileTrashActionOutcome(outcome, state.authenticationFailure == null, onEvent)
            }
        }
        when (val content = state.content) {
            TrashContent.Loading -> item(key = "loading") {
                MobileLoadingState(stringResource(R.string.mobile_trash_loading))
            }
            is TrashContent.Error -> item(key = "error") {
                TrashReadFailure(content.failure) { onEvent(TrashEvent.Retry) }
            }
            is TrashContent.Loaded ->
                loadedTrashItems(content, state, onEvent, onActions = { sheetItemId = it.id.value })
        }
    }
    sheetItem?.let { item ->
        MobileTrashItemSheet(item, state, onEvent, onDismiss = { sheetItemId = null })
    }
    state.actionConfirmation?.let { action ->
        MobileTrashActionConfirmation(action, state, onEvent)
    }
    state.confirmation?.let { item ->
        MobileTrashRestoreConfirmation(item, state, onEvent)
    }
}

private fun LazyListScope.loadedTrashItems(
    content: TrashContent.Loaded,
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    onActions: (TrashItem) -> Unit,
) {
    item(key = "summary") {
        Column(Modifier.fillMaxWidth()) {
            MobileTrashSummary(content)
            if (content.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
    }
    content.refreshFailure?.let { failure ->
        item(key = "refresh-error") { TrashReadFailure(failure) { onEvent(TrashEvent.Refresh) } }
    }
    if (content.items.isEmpty() && content.nextCursor == null) {
        item(key = "empty") {
            MobileEmptyState(stringResource(R.string.mobile_trash_empty_title),
                stringResource(R.string.mobile_trash_empty_message))
        }
    }
    items(content.items, key = { it.id.value }) { item ->
        MobileTrashRow(
            item,
            enabled = state.canRestore(item.id) || state.canDelete(item.id),
            canRestore = state.canRestore(item.id),
            onRestore = { onEvent(TrashEvent.SelectRestore(item.id)) },
            onActions = { onActions(item) },
        )
    }
    item(key = "paging") {
        val pageFailure = content.pageFailure
        when {
            content.isLoadingMore -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            pageFailure != null -> TrashReadFailure(pageFailure) { onEvent(TrashEvent.Retry) }
            content.nextCursor != null -> TextButton(
                onClick = { onEvent(TrashEvent.LoadNextPage) },
                enabled = !content.isRefreshing,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(R.string.mobile_files_load_more)) }
        }
    }
}

@Composable
private fun MobileTrashRestoreConfirmation(item: TrashItem, state: TrashState, onEvent: (TrashEvent) -> Boolean) {
    val confirmationId = state.confirmationId
    AlertDialog(
        onDismissRequest = { onEvent(TrashEvent.CancelRestore) },
        title = { Text(stringResource(R.string.mobile_trash_restore_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.name)
                Text(stringResource(if (item.isFolder) R.string.mobile_trash_restore_folder_message
                    else R.string.mobile_trash_restore_message))
            }
        },
        confirmButton = {
            val canConfirm =
                confirmationId != null && state.authenticationFailure == null && !state.hasPendingMutation
            Button(onClick = { confirmationId?.let { onEvent(TrashEvent.ConfirmRestore(it)) } },
                enabled = canConfirm,
                modifier = Modifier.testTag(MOBILE_TRASH_CONFIRM_TAG)) {
                Text(stringResource(R.string.mobile_trash_restore))
            }
        },
        dismissButton = {
            MobileDialogDismissButton(onClick = { onEvent(TrashEvent.CancelRestore) })
        },
    )
}

/** Size in mono, then how long Trash keeps it; Restore sits on the row, the rest in its sheet. */
@Composable
private fun MobileTrashRow(
    item: TrashItem,
    enabled: Boolean,
    canRestore: Boolean,
    onRestore: () -> Unit,
    onActions: () -> Unit,
) {
    val context = LocalContext.current
    val style = MaterialTheme.typography.bodySmall
    val muted = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    MobileRow(
        leading = { FileTypeIcon(item.type, Modifier.size(26.dp)) },
        headline = { MobileMiddleCutText(item.name) },
        meta = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatFileSize(item.sizeBytes), style = style.putioMono(), maxLines = 1)
                item.expirationDate?.trashDaysLeft(LocalDate.now())?.let { days ->
                    Text("·", style = style, modifier = Modifier.clearAndSetSemantics {})
                    Text(context.goneIn(days), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        trailing = {
            Row {
                IconButton(onClick = onRestore, enabled = canRestore, colors = muted) {
                    Icon(
                        painterResource(R.drawable.ic_ph_arrow_counter_clockwise),
                        contentDescription = stringResource(R.string.mobile_trash_restore_named, item.name),
                    )
                }
                IconButton(onClick = onActions, enabled = enabled, colors = muted) {
                    Icon(
                        painterResource(R.drawable.ic_ph_dots_three_vertical),
                        contentDescription = stringResource(R.string.mobile_trash_actions_named, item.name),
                    )
                }
            }
        },
    )
}

/** The section line: what Trash holds in mono, then how long it keeps items. */
@Composable
private fun MobileTrashSummary(content: TrashContent.Loaded) {
    val style = MaterialTheme.typography.titleSmall
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            content.trashSizeBytes?.let { size ->
                Text(formatFileSize(size), style = style.putioMono(), maxLines = 1)
                Text("·", style = style, modifier = Modifier.clearAndSetSemantics {})
            }
            Text(stringResource(R.string.mobile_trash_retention), style = style)
        }
    }
}

private fun Context.goneIn(days: Long): String = when (days) {
    0L -> getString(R.string.mobile_trash_gone_today)
    1L -> getString(R.string.mobile_trash_gone_tomorrow)
    else -> resources.getQuantityString(R.plurals.mobile_trash_gone_in, days.toInt(), days.toInt())
}

@Composable
private fun TrashReadFailure(failure: PutioFailure, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(failure.trashMessage())
        if (failure !is PutioFailure.AuthenticationRequired) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.mobile_action_retry)) }
        }
    }
}

@Composable
private fun MobileTrashOutcome(
    outcome: TrashRestoreOutcome,
    enabled: Boolean,
    onEvent: (TrashEvent) -> Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag(MOBILE_TRASH_OUTCOME_TAG)
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MobileTrashRestoreSummary(outcome)
        when (outcome.check) {
            TrashRestoreCheck.CHECKING -> {
                Text(stringResource(R.string.mobile_trash_checking))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TrashRestoreCheck.UNAVAILABLE -> Text(stringResource(R.string.mobile_trash_not_available))
            TrashRestoreCheck.FAILED -> outcome.checkFailure?.let { Text(it.trashMessage()) }
            TrashRestoreCheck.NOT_CHECKED, TrashRestoreCheck.AVAILABLE -> Unit
        }
        if (outcome.submission == TrashRestoreSubmission.SUBMITTING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (outcome.isPending) {
            OutlinedButton(onClick = { onEvent(TrashEvent.CheckRestore) },
                enabled = enabled && outcome.check != TrashRestoreCheck.CHECKING,
                modifier = Modifier.testTag(MOBILE_TRASH_CHECK_TAG)) {
                Text(stringResource(R.string.mobile_trash_check_status))
            }
        } else {
            TextButton(onClick = { onEvent(TrashEvent.DismissRestoreOutcome) }) {
                Text(stringResource(R.string.mobile_action_ok))
            }
        }
    }
}

@Composable
private fun MobileTrashRestoreSummary(outcome: TrashRestoreOutcome) {
    val resolved = outcome.resolvedItem
    if (outcome.check == TrashRestoreCheck.AVAILABLE && resolved != null) {
        Text(stringResource(if (resolved.isFolder) R.string.mobile_trash_folder_available
            else R.string.mobile_trash_file_available, resolved.name), style = MaterialTheme.typography.titleMedium)
    } else {
        Text(outcome.item.name, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(outcome.submission.messageResource()))
        outcome.submissionFailure?.let { failure ->
            val incomplete = failure is PutioFailure.ApiRejected &&
                failure.statusCode == 400 && failure.httpStatusCode == 400 &&
                    failure.errorType == "TRASH_INCOMPLETE_TRASH"
            Text(if (incomplete) stringResource(R.string.mobile_trash_incomplete) else failure.trashMessage())
        }
    }
}

private fun TrashRestoreSubmission.messageResource(): Int = when (this) {
    TrashRestoreSubmission.SUBMITTING -> R.string.mobile_trash_submitting
    TrashRestoreSubmission.ACKNOWLEDGED -> R.string.mobile_trash_started
    TrashRestoreSubmission.UNCERTAIN -> R.string.mobile_trash_uncertain
    TrashRestoreSubmission.REJECTED -> R.string.mobile_trash_rejected
}

@Composable
private fun PutioFailure.trashMessage(): String =
    if (this is PutioFailure.AccessDenied) stringResource(R.string.mobile_trash_access_denied) else mobileMessage()
