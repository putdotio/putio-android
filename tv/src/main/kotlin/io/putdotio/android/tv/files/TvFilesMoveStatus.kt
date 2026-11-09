package io.putdotio.android.tv.files

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesMoveOutcome
import io.putdotio.android.files.FilesMoveStatus
import io.putdotio.android.files.pendingIntent
import io.putdotio.android.tv.TvFocusHandBack
import io.putdotio.android.tv.TvSnackbarMessage
import io.putdotio.android.tv.TvSnackbarState

@Composable
internal fun TvFilesMoveStatus(
    current: FilesFolderState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    noticeFocus: TvFocusHandBack,
) {
    val operation = current.operation
    val intent = operation.pendingIntent<FilesFolderOperationIntent.Move>()
    when {
        intent != null && operation is FilesFolderOperation.Loading ->
            TvFilesNotice(text = stringResource(operation.phase.tvMoveProgress()))
        intent != null && operation is FilesFolderOperation.Failed -> {
            val reloading = operation.phase == FilesFolderOperationPhase.RELOADING
            TvFilesNotice(
                text = stringResource(
                    if (reloading) R.string.tv_files_move_reload_error else R.string.tv_files_move_unknown,
                ),
                action = stringResource(if (reloading) R.string.tv_files_retry else R.string.tv_files_check_status),
                onAction = { onEvent(FilesBrowserEvent.Retry) },
                handBack = noticeFocus,
            )
        }
        operation == FilesFolderOperation.Idle ->
            current.moveOutcome?.tvNotice()?.let { TvFilesNotice(text = it.text(), error = true) }
    }
}

@StringRes
private fun FilesFolderOperationPhase.tvMoveProgress(): Int =
    when (this) {
        FilesFolderOperationPhase.MOVING -> R.string.tv_files_moving
        FilesFolderOperationPhase.CHECKING_MOVE -> R.string.tv_files_move_checking
        else -> R.string.tv_files_move_reloading
    }

internal data class TvMoveNotice(@param:StringRes val message: Int, val itemName: String?)

internal fun FilesMoveOutcome.tvNotice(): TvMoveNotice? {
    val intoItself = TvMoveNotice(R.string.tv_files_move_into_itself, null)
    return when (status) {
        FilesMoveStatus.STILL_PRESENT ->
            if (failure is PutioFailure.AccessDenied) {
                intoItself
            } else {
                TvMoveNotice(R.string.tv_files_move_still_present, itemName)
            }
        FilesMoveStatus.REJECTED -> {
            val clash = errors?.singleOrNull()?.takeIf { it.errorType == "NAME_ALREADY_EXIST" }
            when {
                clash != null -> TvMoveNotice(R.string.tv_files_move_collision, clash.name ?: itemName)
                else -> TvMoveNotice(R.string.tv_files_move_rejected, itemName)
            }
        }
        FilesMoveStatus.CHECKING, FilesMoveStatus.UNKNOWN, FilesMoveStatus.MOVED -> null
    }
}

@Composable
private fun TvMoveNotice.text(): String =
    if (itemName != null) stringResource(message, itemName) else stringResource(message)

@Composable
internal fun TvFilesMoveAnnouncement(
    current: FilesFolderState,
    snackbar: TvSnackbarState,
    edits: TvFilesEdits?,
) {
    val moved = current.moveOutcome?.takeIf {
        current.operation == FilesFolderOperation.Idle && it.status == FilesMoveStatus.MOVED
    }
    val text = moved?.let { stringResource(R.string.tv_files_move_moved, it.itemName) }
    LaunchedEffect(moved?.requestId) {
        if (moved != null && text != null && edits?.announceMoveOnce(moved.requestId) == true) {
            snackbar.show(TvSnackbarMessage(text = text))
        }
    }
}

internal fun List<FilesItem>.neighbourOf(item: FilesItem): FilesItem {
    val index = indexOfFirst { it.id == item.id }
    return getOrNull(index + 1) ?: getOrNull(index - 1) ?: item
}
