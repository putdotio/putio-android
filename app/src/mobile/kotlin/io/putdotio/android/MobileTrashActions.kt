package io.putdotio.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.trash.TrashAction
import io.putdotio.android.trash.TrashActionCheck
import io.putdotio.android.trash.TrashActionOutcome
import io.putdotio.android.trash.TrashActionSubmission
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashState

internal const val MOBILE_TRASH_ITEM_SHEET_TAG = "mobile-trash-item-sheet"
internal const val MOBILE_TRASH_ITEM_RESTORE_TAG = "mobile-trash-item-restore"
internal const val MOBILE_TRASH_ITEM_DELETE_TAG = "mobile-trash-item-delete"
internal const val MOBILE_TRASH_RESTORE_ALL_TAG = "mobile-trash-restore-all"
internal const val MOBILE_TRASH_EMPTY_TAG = "mobile-trash-empty"
internal const val MOBILE_TRASH_ACTION_CONFIRM_TAG = "mobile-trash-action-confirm"
internal const val MOBILE_TRASH_ACTION_CHECK_TAG = "mobile-trash-action-check"
internal const val MOBILE_TRASH_ACTION_OUTCOME_TAG = "mobile-trash-action-outcome"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileTrashItemSheet(
    item: TrashItem,
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(MOBILE_TRASH_ITEM_SHEET_TAG),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.mobile_trash_restore)) },
                modifier = Modifier
                    .testTag(MOBILE_TRASH_ITEM_RESTORE_TAG)
                    .clickable(enabled = state.canRestore(item.id), role = Role.Button) {
                        onDismiss()
                        onEvent(TrashEvent.SelectRestore(item.id))
                    },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.mobile_trash_delete), color = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier
                    .testTag(MOBILE_TRASH_ITEM_DELETE_TAG)
                    .clickable(enabled = state.canDelete(item.id), role = Role.Button) {
                        onDismiss()
                        onEvent(TrashEvent.SelectDelete(item.id))
                    }
                    .padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
internal fun MobileTrashBulkActions(state: TrashState, onEvent: (TrashEvent) -> Boolean) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onEvent(TrashEvent.SelectRestoreAll) },
            enabled = state.canActOnAll,
            modifier = Modifier.testTag(MOBILE_TRASH_RESTORE_ALL_TAG),
        ) { Text(stringResource(R.string.mobile_trash_restore_all)) }
        OutlinedButton(
            onClick = { onEvent(TrashEvent.SelectEmpty) },
            enabled = state.canActOnAll,
            modifier = Modifier.testTag(MOBILE_TRASH_EMPTY_TAG),
        ) { Text(stringResource(R.string.mobile_trash_empty_action), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun MobileTrashActionConfirmation(action: TrashAction, state: TrashState, onEvent: (TrashEvent) -> Boolean) {
    val confirmationId = state.actionConfirmationId
    val destructive = action !is TrashAction.RestoreAll
    AlertDialog(
        onDismissRequest = { onEvent(TrashEvent.CancelAction) },
        title = { Text(stringResource(action.titleResource())) },
        text = {
            Text(
                text = when (action) {
                    is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_message, action.item.name)
                    TrashAction.RestoreAll -> stringResource(R.string.mobile_trash_restore_all_message)
                    TrashAction.Empty -> stringResource(R.string.mobile_trash_empty_confirm_message)
                },
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { confirmationId?.let { onEvent(TrashEvent.ConfirmAction(it)) } },
                enabled = confirmationId != null && state.authenticationFailure == null && !state.hasPendingMutation,
                modifier = Modifier.testTag(MOBILE_TRASH_ACTION_CONFIRM_TAG),
            ) {
                Text(
                    stringResource(action.confirmResource()),
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(TrashEvent.CancelAction) }) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}

@Composable
internal fun MobileTrashActionOutcome(
    outcome: TrashActionOutcome,
    enabled: Boolean,
    onEvent: (TrashEvent) -> Boolean,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).testTag(MOBILE_TRASH_ACTION_OUTCOME_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (outcome.action as? TrashAction.DeleteItem)?.let {
            Text(it.item.name, style = MaterialTheme.typography.titleMedium)
        }
        Text(outcome.message())
        outcome.submissionFailure?.takeIf { outcome.submission == TrashActionSubmission.REJECTED }?.let { failure ->
            Text(stringResource(failure.trashActionMessageResource()))
        }
        when (outcome.check) {
            TrashActionCheck.CHECKING -> {
                Text(stringResource(R.string.mobile_trash_action_checking))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TrashActionCheck.FAILED -> outcome.checkFailure?.let {
                Text(stringResource(it.trashActionMessageResource()))
            }
            TrashActionCheck.NOT_CHECKED, TrashActionCheck.VERIFIED, TrashActionCheck.INCONCLUSIVE -> Unit
        }
        if (outcome.submission == TrashActionSubmission.SUBMITTING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (outcome.isPending) {
            OutlinedButton(
                onClick = { onEvent(TrashEvent.CheckAction) },
                enabled = enabled && outcome.check != TrashActionCheck.CHECKING,
                modifier = Modifier.testTag(MOBILE_TRASH_ACTION_CHECK_TAG),
            ) { Text(stringResource(R.string.mobile_trash_check_trash)) }
        } else {
            TextButton(onClick = { onEvent(TrashEvent.DismissActionOutcome) }) {
                Text(stringResource(R.string.mobile_action_ok))
            }
        }
    }
}

@Composable
private fun TrashActionOutcome.message(): String {
    val name = (action as? TrashAction.DeleteItem)?.item?.name.orEmpty()
    return when {
        check == TrashActionCheck.VERIFIED -> when (action) {
            is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_verified, name)
            TrashAction.RestoreAll -> stringResource(R.string.mobile_trash_restore_all_verified)
            TrashAction.Empty -> stringResource(R.string.mobile_trash_empty_verified)
        }
        check == TrashActionCheck.INCONCLUSIVE -> when (action) {
            is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_inconclusive, name)
            else -> stringResource(R.string.mobile_trash_restore_all_inconclusive)
        }
        check == TrashActionCheck.FAILED && checkFailure == null -> when (action) {
            is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_still_present, name)
            else -> stringResource(R.string.mobile_trash_empty_still_present)
        }
        else -> stringResource(when (submission) {
            TrashActionSubmission.SUBMITTING -> R.string.mobile_trash_action_submitting
            TrashActionSubmission.ACKNOWLEDGED -> when (action) {
                is TrashAction.DeleteItem -> R.string.mobile_trash_delete_started
                TrashAction.RestoreAll -> R.string.mobile_trash_restore_all_started
                TrashAction.Empty -> R.string.mobile_trash_empty_started
            }
            TrashActionSubmission.UNCERTAIN -> R.string.mobile_trash_action_uncertain
            TrashActionSubmission.REJECTED -> R.string.mobile_trash_action_rejected
        })
    }
}

private fun TrashAction.titleResource(): Int = when (this) {
    is TrashAction.DeleteItem -> R.string.mobile_trash_delete_title
    TrashAction.RestoreAll -> R.string.mobile_trash_restore_all_title
    TrashAction.Empty -> R.string.mobile_trash_empty_confirm_title
}

private fun TrashAction.confirmResource(): Int = when (this) {
    is TrashAction.DeleteItem -> R.string.mobile_trash_delete
    TrashAction.RestoreAll -> R.string.mobile_trash_restore_all
    TrashAction.Empty -> R.string.mobile_trash_empty_action
}

private fun FilesFailure.trashActionMessageResource(): Int =
    if (this is FilesFailure.AccessDenied) R.string.mobile_trash_access_denied else mobileMessageResource()
