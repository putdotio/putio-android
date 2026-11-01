package io.putdotio.android.tv.trash

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.trash.TrashAction
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashState
import io.putdotio.android.tv.TvConfirmDialog
import io.putdotio.android.tv.files.FILES_SHEET_SCRIM

@Composable
internal fun TvTrashConfirmations(
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
) {
    val canConfirm = state.authenticationFailure == null && !state.hasPendingMutation
    state.confirmation?.let { item ->
        val confirmationId = state.confirmationId
        TvTrashConfirm(
            title = stringResource(R.string.tv_trash_restore_title),
            message = stringResource(R.string.tv_trash_restore_message, item.name),
            confirmLabel = stringResource(R.string.tv_trash_restore),
            destructive = false,
            enabled = canConfirm && confirmationId != null,
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
                state.emptyMessage(),
                stringResource(R.string.tv_trash_empty_action),
            )
        }
        TvTrashConfirm(
            title = title,
            message = message,
            confirmLabel = confirm,
            destructive = action != TrashAction.RestoreAll,
            enabled = canConfirm && confirmationId != null,
            onConfirm = { confirmationId?.let { onEvent(TrashEvent.ConfirmAction(it)) } },
            onCancel = { onEvent(TrashEvent.CancelAction) },
        )
    }
}

@Composable
private fun TrashState.emptyMessage(): String {
    val loaded = content as? TrashContent.Loaded
    val count = loaded?.total ?: loaded?.items?.size?.takeIf { loaded.nextCursor == null }
    return if (count != null && count > 0) {
        pluralStringResource(R.plurals.tv_trash_empty_count_message, count, count)
    } else {
        stringResource(R.string.tv_trash_empty_message_confirm)
    }
}

@Composable
private fun TvTrashConfirm(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    TvConfirmDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onCancel,
        destructive = destructive,
        balanceMessage = true,
        dimAmount = FILES_SHEET_SCRIM.alpha,
        confirmEnabled = enabled,
    )
}
