package io.putdotio.android.trash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileDialogDismissButton
import io.putdotio.android.MobileSheetHandle
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioDestructiveButton
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.files.mobileMessage
import io.putdotio.android.MobileIconButton

internal const val MOBILE_TRASH_ITEM_SHEET_TAG = "mobile-trash-item-sheet"
internal const val MOBILE_TRASH_ITEM_RESTORE_TAG = "mobile-trash-item-restore"
internal const val MOBILE_TRASH_ITEM_DELETE_TAG = "mobile-trash-item-delete"
internal const val MOBILE_TRASH_RESTORE_ALL_TAG = "mobile-trash-restore-all"
internal const val MOBILE_TRASH_EMPTY_TAG = "mobile-trash-empty"
internal const val MOBILE_TRASH_MORE_TAG = "mobile-trash-more"
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
        dragHandle = { MobileSheetHandle() },
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
                        onEvent(TrashEvent.Restore(item.id))
                    },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.mobile_trash_delete), color = PutioDesignTokens.redTextSecondary)
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

/** The overflow Trash's top bar carries, while Trash is the route on screen. */
internal fun trashTopBarActions(
    route: String?,
    state: TrashState?,
    controller: TrashController?,
    selection: TrashSelection,
): (@Composable RowScope.() -> Unit)? =
    if (route == MOBILE_TRASH_ROUTE && state != null && controller != null) {
        { MobileTrashOverflow(state, controller::dispatch, onSelect = selection::start) }
    } else {
        null
    }

/** Select, Refresh, Restore all and Empty trash, from the overflow in Trash's top bar. */
@Composable
internal fun MobileTrashOverflow(state: TrashState, onEvent: (TrashEvent) -> Boolean, onSelect: () -> Unit) {
    val content = state.content as? TrashContent.Loaded
    var expanded by remember { mutableStateOf(false) }
    val choose = { event: TrashEvent ->
        expanded = false
        onEvent(event)
        Unit
    }
    Box {
        MobileIconButton(
            stringResource(R.string.mobile_trash_more),
            R.drawable.ic_ph_dots_three_vertical,
            onClick = { expanded = true },
            modifier = Modifier.testTag(MOBILE_TRASH_MORE_TAG),
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(PutioRadius.large),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_trash_select)) },
                onClick = {
                    expanded = false
                    onSelect()
                },
                enabled = content?.items.orEmpty().isNotEmpty(),
                modifier = Modifier.testTag(MOBILE_TRASH_SELECT_TAG),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_action_refresh)) },
                onClick = { choose(TrashEvent.Refresh) },
                enabled = content != null && !content.isRefreshing && !content.isLoadingMore,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_trash_restore_all)) },
                onClick = { choose(TrashEvent.RestoreAll) },
                enabled = state.canActOnAll,
                modifier = Modifier.testTag(MOBILE_TRASH_RESTORE_ALL_TAG),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_trash_empty_action)) },
                onClick = { choose(TrashEvent.SelectEmpty) },
                enabled = state.canActOnAll,
                colors = MenuDefaults.itemColors(textColor = PutioDesignTokens.redTextSecondary),
                modifier = Modifier.testTag(MOBILE_TRASH_EMPTY_TAG),
            )
        }
    }
}

@Composable
internal fun MobileTrashActionConfirmation(
    action: TrashDestructiveAction,
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
) {
    val confirmationId = state.actionConfirmationId
    AlertDialog(
        onDismissRequest = { onEvent(TrashEvent.CancelAction) },
        title = { Text(action.title()) },
        text = {
            Text(
                text = when (action) {
                    is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_message, action.item.name)
                    is TrashAction.DeleteSelected -> pluralStringResource(
                        R.plurals.mobile_trash_delete_selected_message,
                        action.items.size,
                    )
                    TrashAction.Empty -> stringResource(R.string.mobile_trash_empty_confirm_message)
                },
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            PutioDestructiveButton(
                onClick = { confirmationId?.let { onEvent(TrashEvent.ConfirmAction(it)) } },
                enabled = confirmationId != null && state.authenticationFailure == null && !state.hasPendingMutation,
                modifier = Modifier.testTag(MOBILE_TRASH_ACTION_CONFIRM_TAG),
            ) { Text(stringResource(action.confirmResource())) }
        },
        dismissButton = {
            MobileDialogDismissButton(onClick = { onEvent(TrashEvent.CancelAction) })
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
            Text(failure.trashActionMessage())
        }
        when (outcome.check) {
            TrashActionCheck.CHECKING -> {
                Text(stringResource(R.string.mobile_trash_action_checking))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TrashActionCheck.FAILED -> outcome.checkFailure?.let {
                Text(it.trashActionMessage())
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
private fun TrashActionOutcome.message(): String =
    when {
        check == TrashActionCheck.VERIFIED -> action.verifiedMessage()
        check == TrashActionCheck.INCONCLUSIVE -> action.inconclusiveMessage()
        check == TrashActionCheck.FAILED && checkFailure == null -> action.stillPresentMessage(stillListed.size)
        else -> submissionMessage()
    }

@Composable
private fun TrashAction.startedMessage(): String = when (this) {
    is TrashAction.DeleteSelected ->
        pluralStringResource(R.plurals.mobile_trash_delete_selected_started, items.size, items.size)
    is TrashAction.RestoreSelected ->
        pluralStringResource(R.plurals.mobile_trash_restore_selected_started, items.size, items.size)
    is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_started)
    TrashAction.RestoreAll -> stringResource(R.string.mobile_trash_restore_all_started)
    TrashAction.Empty -> stringResource(R.string.mobile_trash_empty_started)
}

@Composable
private fun TrashAction.verifiedMessage(): String = when (this) {
    is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_verified, item.name)
    is TrashAction.DeleteSelected ->
        pluralStringResource(R.plurals.mobile_trash_delete_selected_verified, items.size, items.size)
    TrashAction.RestoreAll, is TrashAction.RestoreSelected -> stringResource(R.string.mobile_trash_restore_all_verified)
    TrashAction.Empty -> stringResource(R.string.mobile_trash_empty_verified)
}

@Composable
private fun TrashAction.inconclusiveMessage(): String = when (this) {
    is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_inconclusive, item.name)
    is TrashAction.DeleteSelected -> stringResource(R.string.mobile_trash_delete_selected_inconclusive)
    else -> stringResource(R.string.mobile_trash_restore_all_inconclusive)
}

@Composable
private fun TrashAction.stillPresentMessage(failed: Int): String = when (this) {
    is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_still_present, item.name)
    is TrashAction.DeleteSelected -> pluralStringResource(R.plurals.mobile_trash_delete_selected_failed, failed, failed)
    else -> stringResource(R.string.mobile_trash_empty_still_present)
}

@Composable
private fun TrashActionOutcome.submissionMessage(): String = when (submission) {
    TrashActionSubmission.SUBMITTING -> stringResource(R.string.mobile_trash_action_submitting)
    TrashActionSubmission.ACKNOWLEDGED -> action.startedMessage()
    TrashActionSubmission.UNCERTAIN -> stringResource(R.string.mobile_trash_action_uncertain)
    TrashActionSubmission.REJECTED -> stringResource(R.string.mobile_trash_action_rejected)
}

@Composable
private fun TrashDestructiveAction.title(): String = when (this) {
    is TrashAction.DeleteItem -> stringResource(R.string.mobile_trash_delete_title)
    is TrashAction.DeleteSelected ->
        pluralStringResource(R.plurals.mobile_trash_delete_selected_title, items.size, items.size)
    TrashAction.Empty -> stringResource(R.string.mobile_trash_empty_confirm_title)
}

private fun TrashDestructiveAction.confirmResource(): Int = when (this) {
    is TrashAction.DeleteItem -> R.string.mobile_trash_delete
    is TrashAction.DeleteSelected -> R.string.mobile_files_delete
    TrashAction.Empty -> R.string.mobile_trash_empty_action
}

@Composable
private fun PutioFailure.trashActionMessage(): String =
    if (this is PutioFailure.AccessDenied) stringResource(R.string.mobile_trash_access_denied) else mobileMessage()
