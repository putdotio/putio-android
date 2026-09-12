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

/** Center on a row: the item's actions as a choice dialog, Restore first. */
@Composable
internal fun TvTrashItemDialog(
    item: TrashItem,
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    TvDialog(title = item.name, message = null, onDismiss = onDismiss) { focus ->
        TvButton(
            onClick = {
                onDismiss()
                if (state.canRestore(item.id)) onEvent(TrashEvent.SelectRestore(item.id))
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        ) {
            Text(stringResource(R.string.tv_trash_restore))
        }
        TvButton(
            onClick = {
                onDismiss()
                if (state.canDelete(item.id)) onEvent(TrashEvent.SelectDelete(item.id))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.tv_trash_delete))
        }
        TvButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
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
            onConfirm = { if (canConfirm && confirmationId != null) onEvent(TrashEvent.ConfirmRestore(confirmationId)) },
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
            onConfirm = { if (canConfirm && confirmationId != null) onEvent(TrashEvent.ConfirmAction(confirmationId)) },
            onCancel = { onEvent(TrashEvent.CancelAction) },
        )
    }
}

@Composable
private fun TvConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    TvDialog(title = title, message = message, onDismiss = onCancel) { focus ->
        TvButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text(confirmLabel) }
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
