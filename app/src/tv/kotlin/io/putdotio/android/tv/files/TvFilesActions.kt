package io.putdotio.android.tv.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.files.FilesItem
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
 */
internal fun FilesItem.tvActions(
    watchedToggleEnabled: Boolean,
    trashEnabled: Boolean?,
    canDelete: Boolean,
): List<TvFilesAction> =
    buildList {
        if (isPlayable) add(TvFilesAction.OpenInVlc)
        val watched = playback?.isWatched == true
        if (type == PutioFileType.VIDEO && watchedToggleEnabled && (watched || playback?.durationSeconds != null)) {
            add(TvFilesAction.SetWatched(!watched))
        }
        if (trashEnabled != null && canDelete && id.value > 0L) add(TvFilesAction.Delete(trashEnabled))
    }

@Composable
internal fun TvFilesAction.label(): String =
    when (this) {
        TvFilesAction.OpenInVlc -> stringResource(R.string.tv_files_action_vlc)
        is TvFilesAction.SetWatched ->
            stringResource(if (watched) R.string.tv_files_action_mark_watched else R.string.tv_files_action_mark_unwatched)
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
            modifier = Modifier.fillMaxWidth().then(if (actions.isEmpty()) Modifier.focusRequester(focus) else Modifier),
        ) { Text(stringResource(R.string.tv_files_cancel)) }
    }
}

/** Deletion confirms first with Cancel focused; the copy says whether Trash keeps the file. */
@Composable
internal fun TvFilesDeleteDialog(
    item: FilesItem,
    trash: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TvDialog(
        title = stringResource(if (trash) R.string.tv_files_trash_title else R.string.tv_files_delete_title),
        message = stringResource(if (trash) R.string.tv_files_trash_message else R.string.tv_files_delete_message, item.name),
        onDismiss = onDismiss,
    ) { focus ->
        TvButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (trash) R.string.tv_files_action_trash else R.string.tv_files_action_delete))
        }
        TvButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
            Text(stringResource(R.string.tv_files_cancel))
        }
    }
}

/**
 * Hands the original file to VLC. False when VLC is not installed; the URL carries the
 * session token and goes only into the intent, never into a log or a message.
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
