package io.putdotio.android.tv.trash

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.trash.TrashAction
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashState
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvDialog

/**
 * Center on a row: the item's actions as a choice dialog, Restore first. An action the
 * controller would refuse is disabled; focus lands on the first one it allows.
 */
@Composable
internal fun TvTrashItemDialog(
    item: TrashItem,
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    val canRestore = state.canRestore(item.id)
    val canDelete = state.canDelete(item.id)
    TvDialog(title = item.name, message = null, onDismiss = onDismiss) { focus ->
        TvButton(
            onClick = {
                onDismiss()
                onEvent(TrashEvent.SelectRestore(item.id))
            },
            enabled = canRestore,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (canRestore) Modifier.focusRequester(focus) else Modifier),
        ) {
            Text(stringResource(R.string.tv_trash_restore))
        }
        TvButton(
            onClick = {
                onDismiss()
                onEvent(TrashEvent.SelectDelete(item.id))
            },
            enabled = canDelete,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!canRestore && canDelete) Modifier.focusRequester(focus) else Modifier),
        ) {
            Text(stringResource(R.string.tv_trash_delete))
        }
        TvButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!canRestore && !canDelete) Modifier.focusRequester(focus) else Modifier),
        ) {
            Text(stringResource(R.string.tv_trash_cancel))
        }
    }
}

/** The controller's pending confirmations; focus lands on Cancel since none is undoable in the UI. */
@Composable
internal fun TvTrashConfirmations(
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
) {
    val canConfirm = state.authenticationFailure == null && !state.hasPendingMutation
    state.confirmation?.let { item ->
        val confirmationId = state.confirmationId
        TvConfirmDialog(
            title = stringResource(R.string.tv_trash_restore_title),
            message = stringResource(R.string.tv_trash_restore_message, item.name),
            confirmLabel = stringResource(R.string.tv_trash_restore),
            confirmEnabled = canConfirm && confirmationId != null,
            onConfirm = { confirmationId?.let { onEvent(TrashEvent.ConfirmRestore(it)) } },
            onCancel = { onEvent(TrashEvent.CancelRestore) },
        )
    }
    state.actionConfirmation?.let { action ->
        val confirmationId = state.actionConfirmationId
        val (title, message, confirm) = when (action) {
            is TrashAction.DeleteItem -> Triple(
                stringResource(R.string.tv_trash_delete_title),
                stringResource(R.string.tv_trash_delete_message, action.item.name),
                stringResource(R.string.tv_trash_delete),
            )
            TrashAction.RestoreAll -> Triple(
                stringResource(R.string.tv_trash_restore_all_title),
                stringResource(R.string.tv_trash_restore_all_message),
                stringResource(R.string.tv_trash_restore_all),
            )
            TrashAction.Empty -> Triple(
                stringResource(R.string.tv_trash_empty_title_confirm),
                stringResource(R.string.tv_trash_empty_message_confirm),
                stringResource(R.string.tv_trash_empty_action),
            )
        }
        TvConfirmDialog(
            title = title,
            message = message,
            confirmLabel = confirm,
            confirmEnabled = canConfirm && confirmationId != null,
            onConfirm = { confirmationId?.let { onEvent(TrashEvent.ConfirmAction(it)) } },
            onCancel = { onEvent(TrashEvent.CancelAction) },
        )
    }
}

@Composable
private fun TvConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    TvDialog(title = title, message = message, onDismiss = onCancel) { focus ->
        // Disabled, not a silent no-op, while a 401 or another mutation makes confirming pointless.
        TvButton(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.fillMaxWidth()) { Text(confirmLabel) }
        TvButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        ) {
            Text(stringResource(R.string.tv_trash_cancel))
        }
    }
}
