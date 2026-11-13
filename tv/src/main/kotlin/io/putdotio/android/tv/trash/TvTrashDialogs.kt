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
    state.actionConfirmation?.let { action ->
        val confirmationId = state.actionConfirmationId
        val (title, message, confirm) = when (action) {
            is TrashAction.DeleteItem -> Triple(
                stringResource(R.string.tv_trash_delete_title),
                stringResource(R.string.tv_trash_delete_message, action.item.name),
                stringResource(R.string.tv_trash_delete),
            )
            is TrashAction.DeleteSelected -> Triple(
                pluralStringResource(R.plurals.tv_trash_delete_selected_title, action.items.size, action.items.size),
                pluralStringResource(R.plurals.tv_trash_delete_selected_message, action.items.size),
                stringResource(R.string.tv_trash_delete),
            )
            TrashAction.Empty -> Triple(
                stringResource(R.string.tv_trash_empty_title_confirm),
                state.emptyMessage(),
                stringResource(R.string.tv_trash_empty_action),
            )
        }
        TvConfirmDialog(
            title = title,
            message = message,
            confirmLabel = confirm,
            onConfirm = { confirmationId?.let { onEvent(TrashEvent.ConfirmAction(it)) } },
            onDismiss = { onEvent(TrashEvent.CancelAction) },
            destructive = true,
            balanceMessage = true,
            dimAmount = FILES_SHEET_SCRIM.alpha,
            confirmEnabled = canConfirm && confirmationId != null,
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
