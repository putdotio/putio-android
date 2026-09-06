package io.putdotio.android

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashRestoreOutcome
import io.putdotio.android.trash.TrashRestoreCheck
import io.putdotio.android.trash.TrashRestoreSubmission
import io.putdotio.android.trash.TrashState
import io.putdotio.android.trash.parseTrashTimestamp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

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
    LazyColumn(modifier.fillMaxSize().testTag(MOBILE_TRASH_LIST_TAG)) {
        state.restoreOutcome?.let { outcome ->
            item(key = "restore-outcome") {
                MobileTrashOutcome(outcome, state.authenticationFailure == null, onEvent)
            }
        }
        when (val content = state.content) {
            TrashContent.Loading -> item(key = "loading") {
                MobileLoadingState(stringResource(R.string.mobile_trash_loading))
            }
            is TrashContent.Error -> item(key = "error") {
                TrashReadFailure(content.failure) { onEvent(TrashEvent.Retry) }
            }
            is TrashContent.Loaded -> {
                item(key = "summary") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        content.trashSizeBytes?.let { size ->
                            Text(stringResource(R.string.mobile_trash_storage,
                                Formatter.formatShortFileSize(LocalContext.current, size)))
                        }
                        Text(stringResource(R.string.mobile_trash_expiration_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { onEvent(TrashEvent.Refresh) },
                            enabled = !content.isRefreshing && !content.isLoadingMore) {
                            Text(stringResource(R.string.mobile_action_refresh))
                        }
                        if (content.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                    MobileTrashRow(item, enabled = state.canRestore(item.id),
                        onRestore = { onEvent(TrashEvent.SelectRestore(item.id)) })
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
                item(key = "paging") {
                    when {
                        content.isLoadingMore -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        content.pageFailure != null -> TrashReadFailure(content.pageFailure) { onEvent(TrashEvent.Retry) }
                        content.nextCursor != null -> TextButton(
                            onClick = { onEvent(TrashEvent.LoadNextPage) },
                            enabled = !content.isRefreshing,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text(stringResource(R.string.mobile_files_load_more)) }
                    }
                }
            }
        }
    }
    state.confirmation?.let { item ->
        val confirmationId = state.confirmationId
        AlertDialog(
            onDismissRequest = { onEvent(TrashEvent.CancelRestore) },
            title = { Text(stringResource(R.string.mobile_trash_restore_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name)
                    Text(stringResource(if (item.isFolder) R.string.mobile_trash_restore_folder_message
                        else R.string.mobile_trash_restore_message))
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmationId?.let { onEvent(TrashEvent.ConfirmRestore(it)) } },
                    enabled = confirmationId != null && state.authenticationFailure == null && !state.hasPendingRestore,
                    modifier = Modifier.testTag(MOBILE_TRASH_CONFIRM_TAG)) {
                    Text(stringResource(R.string.mobile_trash_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(TrashEvent.CancelRestore) }) {
                    Text(stringResource(R.string.mobile_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MobileTrashRow(item: TrashItem, enabled: Boolean, onRestore: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(item.name) },
        supportingContent = {
            Column {
                Text(Formatter.formatShortFileSize(context, item.sizeBytes))
                item.deletedAt?.trashDisplayDate(context)?.let { Text(stringResource(R.string.mobile_trash_deleted, it)) }
                item.expirationDate?.trashDisplayDate(context)?.let { Text(stringResource(R.string.mobile_trash_expires, it)) }
            }
        },
        leadingContent = { FileTypeIcon(item.type, Modifier.size(24.dp)) },
        trailingContent = {
            IconButton(onClick = onRestore, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_ph_dots_three_vertical),
                    contentDescription = stringResource(R.string.mobile_trash_restore_named, item.name))
            }
        },
    )
}

@Composable
private fun TrashReadFailure(failure: FilesFailure, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(failure.trashMessageResource()))
        if (failure !is FilesFailure.AuthenticationRequired) {
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
        val resolved = outcome.resolvedItem
        if (outcome.check == TrashRestoreCheck.AVAILABLE && resolved != null) {
            Text(stringResource(if (resolved.isFolder) R.string.mobile_trash_folder_available
                else R.string.mobile_trash_file_available, resolved.name), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(outcome.item.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (outcome.submission) {
                TrashRestoreSubmission.SUBMITTING -> R.string.mobile_trash_submitting
                TrashRestoreSubmission.ACKNOWLEDGED -> R.string.mobile_trash_started
                TrashRestoreSubmission.UNCERTAIN -> R.string.mobile_trash_uncertain
                TrashRestoreSubmission.REJECTED -> R.string.mobile_trash_rejected
            }))
            outcome.submissionFailure?.let { failure ->
                val incomplete = failure is FilesFailure.ApiRejected &&
                    failure.statusCode == 400 && failure.httpStatusCode == 400 && failure.errorType == "TRASH_INCOMPLETE_TRASH"
                Text(stringResource(if (incomplete) R.string.mobile_trash_incomplete else failure.trashMessageResource()))
            }
        }
        when (outcome.check) {
            TrashRestoreCheck.CHECKING -> {
                Text(stringResource(R.string.mobile_trash_checking))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TrashRestoreCheck.UNAVAILABLE -> Text(stringResource(R.string.mobile_trash_not_available))
            TrashRestoreCheck.FAILED -> outcome.checkFailure?.let { Text(stringResource(it.trashMessageResource())) }
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

private fun FilesFailure.trashMessageResource(): Int =
    if (this is FilesFailure.AccessDenied) R.string.mobile_trash_access_denied else mobileMessageResource()

// Unknown formats omit the date instead of fabricating a retention deadline.
internal fun String.trashDisplayDate(context: Context): String? {
    val timestamp = parseTrashTimestamp(this)?.toEpochMilli() ?: run {
        try {
            LocalDate.parse(this).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            return null
        }
    }
    return DateUtils.formatDateTime(context, timestamp,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)
}
