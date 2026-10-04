package io.putdotio.android.tv.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.canStartOperation
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvDialog
import io.putdotio.sdk.files.PutioFileType

/** What a row offers besides opening it, per the oracle's files-actions state. */
internal sealed interface TvFilesAction {
    data object OpenInVlc : TvFilesAction

    data class SetWatched(val watched: Boolean) : TvFilesAction

    data class Delete(val trash: Boolean) : TvFilesAction
}

/**
 * The actions a row can offer. VLC takes the original file for any media row; the watched
 * toggle needs the account to keep positions (`use_start_from`) and, to mark watched, a
 * known duration; deletion needs the confirmed trash setting so the wording is honest.
 * Friends' shared files and the shared folders keep only VLC, as in tv-native.
 */
internal fun FilesItem.tvActions(
    watchedToggleEnabled: Boolean,
    trashEnabled: Boolean?,
    canDelete: Boolean,
): List<TvFilesAction> =
    buildList {
        if (isPlayable) add(TvFilesAction.OpenInVlc)
        val watched = playback?.isWatched == true
        if (!acceptsOwnerActions) return@buildList
        val canToggleWatched = watched || playback?.durationSeconds != null
        if (type == PutioFileType.VIDEO && watchedToggleEnabled && canToggleWatched) {
            add(TvFilesAction.SetWatched(!watched))
        }
        if (trashEnabled != null && canDelete && id.value > 0L) add(TvFilesAction.Delete(trashEnabled))
    }

/** Otherwise the reducer would refuse the Delete a permanent-deletion confirmation sends. */
internal fun FilesFolderState.permanentDeleteConfirmable(trashEnabled: Boolean?): Boolean =
    trashEnabled == false && operation.canStartOperation

/** The open row's actions, or the confirmation for its permanent deletion. */
@Composable
internal fun TvFilesActionsMenu(
    item: FilesItem,
    folder: FilesFolderState,
    trashEnabled: Boolean?,
    watchedToggleEnabled: Boolean,
    confirmingPermanentDelete: Boolean,
    onConfirmPermanentDelete: () -> Unit,
    trashPressed: Boolean,
    onTrashPressedChange: (Boolean) -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onOpenInVlc: (FilesItem) -> Unit,
    onSetWatched: (FilesItem, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val canStartOperation = folder.operation.canStartOperation
    val delete = { mode: FilesDeleteMode ->
        onClose()
        onEvent(FilesBrowserEvent.Delete(folder.folder.id, item.id, mode))
    }
    if (trashPressed) {
        val trashStillOn = trashEnabled == true && canStartOperation
        SideEffect {
            onTrashPressedChange(false)
            if (trashStillOn) delete(FilesDeleteMode.TRASH)
        }
    }
    if (confirmingPermanentDelete && folder.permanentDeleteConfirmable(trashEnabled)) {
        TvFilesDeleteDialog(
            item = item,
            onConfirm = { delete(FilesDeleteMode.PERMANENT) },
            onDismiss = onClose,
        )
    } else {
        TvFilesActionsDialog(
            item = item,
            actions = item.tvActions(watchedToggleEnabled, trashEnabled, canStartOperation),
            onAction = { action ->
                when (action) {
                    TvFilesAction.OpenInVlc -> {
                        onClose()
                        onOpenInVlc(item)
                    }
                    is TvFilesAction.SetWatched -> {
                        onClose()
                        onSetWatched(item, action.watched)
                    }
                    is TvFilesAction.Delete ->
                        if (action.trash) onTrashPressedChange(true) else onConfirmPermanentDelete()
                }
            },
            onDismiss = onClose,
        )
    }
}

@Composable
internal fun TvFilesAction.label(): String =
    when (this) {
        TvFilesAction.OpenInVlc -> stringResource(R.string.tv_files_action_vlc)
        is TvFilesAction.SetWatched ->
            stringResource(
                if (watched) R.string.tv_files_action_mark_watched else R.string.tv_files_action_mark_unwatched,
            )
        is TvFilesAction.Delete ->
            stringResource(if (trash) R.string.tv_files_action_trash else R.string.tv_files_action_delete)
    }

/** Centred menu with one full-width button per action and Cancel last; the first action takes focus. */
@Composable
internal fun TvFilesActionsDialog(
    item: FilesItem,
    actions: List<TvFilesAction>,
    onAction: (TvFilesAction) -> Unit,
    onDismiss: () -> Unit,
) {
    TvDialog(
        title = item.name,
        message = null,
        onDismiss = onDismiss,
    ) { focus ->
        actions.forEachIndexed { index, action ->
            TvButton(
                onClick = { onAction(action) },
                modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(focus) else Modifier),
            ) { Text(action.label()) }
        }
        TvButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().then(
                if (actions.isEmpty()) Modifier.focusRequester(focus) else Modifier,
            ),
        ) { Text(stringResource(R.string.tv_files_cancel)) }
    }
}

/** Permanent deletion confirms first with Cancel focused; Move to trash needs no confirmation. */
@Composable
internal fun TvFilesDeleteDialog(
    item: FilesItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TvDialog(
        title = stringResource(R.string.tv_files_delete_title),
        message = stringResource(R.string.tv_files_delete_message, item.name),
        onDismiss = onDismiss,
    ) { focus ->
        TvButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tv_files_action_delete))
        }
        TvButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
            Text(stringResource(R.string.tv_files_cancel))
        }
    }
}

/**
 * Hands the original file to VLC. False when VLC is not installed; the URL carries the
 * account's download token and goes only into the intent, never into a log or a message.
 */
internal fun launchVlc(context: Context, streamUrl: String, item: FilesItem): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(streamUrl.toUri(), if (item.type == PutioFileType.AUDIO) "audio/*" else "video/*")
        .setPackage(VLC_PACKAGE)
        .putExtra("title", item.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private const val VLC_PACKAGE = "org.videolan.vlc"
