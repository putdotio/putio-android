package io.putdotio.android.tv.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.net.toUri
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.files.canStartOperation
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.sdk.files.PutioFileType

internal sealed interface TvFilesAction {
    sealed interface Tile : TvFilesAction

    data object Resume : Tile

    data object Play : Tile

    data object StartOver : Tile

    data class PlayWith(val method: StreamingMethod) : TvFilesAction

    data object OpenInVlc : TvFilesAction

    data class SetWatched(val watched: Boolean) : TvFilesAction

    data class SetPinned(val pinned: Boolean) : TvFilesAction

    data object Rename : TvFilesAction

    data object Move : TvFilesAction

    data object GoToFolder : TvFilesAction

    data object RemoveFromContinueWatching : TvFilesAction

    data class Delete(val trash: Boolean) : TvFilesAction
}

internal data class TvFilesActionContext(
    val watchedToggleEnabled: Boolean,
    val trashEnabled: Boolean?,
    val canDelete: Boolean,
    val resumePlayback: Boolean = true,
    val pinned: Boolean? = null,
    val playbackMethod: StreamingMethod? = null,
    val canRename: Boolean = false,
    val canMove: Boolean = false,
)

internal fun FilesItem.tvActions(context: TvFilesActionContext): List<TvFilesAction> =
    buildList {
        if (isPlayable) addAll(playActions(context))
        if (!acceptsOwnerActions) return@buildList
        val watchedToggle = when {
            playback?.durationSeconds != null -> TvFilesAction.SetWatched(!showsWatched)
            playback?.isWatched == true -> TvFilesAction.SetWatched(false)
            else -> null
        }
        if (type == PutioFileType.VIDEO && context.watchedToggleEnabled && watchedToggle != null) add(watchedToggle)
        addAll(listedActions(context))
    }

private fun FilesItem.listedActions(context: TvFilesActionContext): List<TvFilesAction> =
    buildList {
        if (id.value <= 0L) return@buildList
        context.pinned?.let { add(TvFilesAction.SetPinned(!it)) }
        if (context.canRename) add(TvFilesAction.Rename)
        if (context.canMove) add(TvFilesAction.Move)
        val trash = context.trashEnabled
        if (trash != null && context.canDelete) add(TvFilesAction.Delete(trash))
    }

private fun FilesItem.playActions(context: TvFilesActionContext): List<TvFilesAction> =
    buildList {
        if (resumePosition(context.resumePlayback) != null) {
            add(TvFilesAction.Resume)
            add(TvFilesAction.StartOver)
        } else {
            add(TvFilesAction.Play)
        }
        val method = context.playbackMethod
        if (type == PutioFileType.VIDEO && method != null) add(TvFilesAction.PlayWith(method.other()))
        add(TvFilesAction.OpenInVlc)
    }

private fun StreamingMethod.other(): StreamingMethod =
    when (this) {
        StreamingMethod.Hls -> StreamingMethod.File
        StreamingMethod.File -> StreamingMethod.Hls
    }

/** Otherwise the reducer would refuse the Delete a permanent-deletion confirmation sends. */
internal fun FilesFolderState.permanentDeleteConfirmable(trashEnabled: Boolean?): Boolean =
    trashEnabled == false && operation.canStartOperation

internal class TvFilesActionHandlers(
    val onPlay: (FilesItem, TvPlayStart) -> Unit,
    val onPlayWith: (FilesItem, StreamingMethod) -> Unit,
    val onOpenInVlc: (FilesItem) -> Unit,
    val onSetWatched: (FilesItem, Boolean) -> Unit,
    val onSetPinned: (FilesItem, Boolean) -> Unit,
    val onEdit: (TvFilesAction, FilesItem) -> Unit = { _, _ -> },
)

internal fun TvFilesActionHandlers.run(action: TvFilesAction, item: FilesItem) {
    when (action) {
        TvFilesAction.Resume -> onPlay(item, TvPlayStart.Resume)
        TvFilesAction.Play -> onPlay(item, TvPlayStart.Resume)
        TvFilesAction.StartOver -> onPlay(item, TvPlayStart.StartOver)
        is TvFilesAction.PlayWith -> onPlayWith(item, action.method)
        TvFilesAction.OpenInVlc -> onOpenInVlc(item)
        is TvFilesAction.SetWatched -> onSetWatched(item, action.watched)
        is TvFilesAction.SetPinned -> onSetPinned(item, action.pinned)
        TvFilesAction.Rename, TvFilesAction.Move, TvFilesAction.GoToFolder -> onEdit(action, item)
        is TvFilesAction.Delete, TvFilesAction.RemoveFromContinueWatching -> Unit
    }
}

/** Hands a ready stream to VLC; the notice to show instead, if any. */
@StringRes
internal fun FilesStreamUrlResult.openInVlc(
    context: Context,
    name: String,
    audio: Boolean,
    failureNotice: (PutioFailure) -> Int?,
): Int? =
    when (this) {
        is FilesStreamUrlResult.Ready ->
            R.string.tv_files_vlc_missing.takeUnless { launchVlc(context, url, name, audio) }
        FilesStreamUrlResult.DownloadTokenUnavailable -> R.string.tv_files_stream_unavailable
        is FilesStreamUrlResult.Failure -> failureNotice(failure)
    }

internal fun Context.hasVlc(): Boolean = packageManager.run {
    getLeanbackLaunchIntentForPackage(VLC_PACKAGE) ?: getLaunchIntentForPackage(VLC_PACKAGE)
} != null

/**
 * Hands the original file to VLC. False when VLC is not installed; the URL carries the
 * account's download token and goes only into the intent, never into a log or a message.
 */
private fun launchVlc(context: Context, streamUrl: String, name: String, audio: Boolean): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(streamUrl.toUri(), if (audio) "audio/*" else "video/*")
        .setPackage(VLC_PACKAGE)
        .putExtra("title", name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private const val VLC_PACKAGE = "org.videolan.vlc"
