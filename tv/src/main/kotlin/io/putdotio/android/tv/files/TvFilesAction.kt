package io.putdotio.android.tv.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.canStartOperation
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.toDisplayDate
import io.putdotio.android.tv.TvConfirmDialog
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.TvSheetRows
import io.putdotio.android.tv.TvSideSheet
import io.putdotio.android.tv.asMonoRun
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.tvAccent
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.android.design.R as DesignR

internal sealed interface TvFilesAction {
    sealed interface Tile : TvFilesAction

    data object Resume : Tile

    data object Play : Tile

    data object StartOver : Tile

    data object Subtitles : Tile

    data object OpenInVlc : TvFilesAction

    data class SetWatched(val watched: Boolean) : TvFilesAction

    data class SetPinned(val pinned: Boolean) : TvFilesAction

    data class Delete(val trash: Boolean) : TvFilesAction
}

internal data class TvFilesActionContext(
    val watchedToggleEnabled: Boolean,
    val trashEnabled: Boolean?,
    val canDelete: Boolean,
    val resumePlayback: Boolean = true,
    val pinned: Boolean? = null,
    val showSubtitles: Boolean = true,
)

internal fun FilesItem.tvActions(context: TvFilesActionContext): List<TvFilesAction> =
    buildList {
        if (isPlayable) addAll(playActions(context))
        val watched = playback?.isWatched == true
        if (!acceptsOwnerActions) return@buildList
        val canToggleWatched = watched || playback?.durationSeconds != null
        if (type == PutioFileType.VIDEO && context.watchedToggleEnabled && canToggleWatched) {
            add(TvFilesAction.SetWatched(!watched))
        }
        val pinned = context.pinned
        if (pinned != null && id.value > 0L) add(TvFilesAction.SetPinned(!pinned))
        val trash = context.trashEnabled
        if (trash != null && context.canDelete && id.value > 0L) add(TvFilesAction.Delete(trash))
    }

private fun FilesItem.playActions(context: TvFilesActionContext): List<TvFilesAction> =
    buildList {
        if (resumePosition(context.resumePlayback) != null) {
            add(TvFilesAction.Resume)
            add(TvFilesAction.StartOver)
        } else {
            add(TvFilesAction.Play)
        }
        if (type == PutioFileType.VIDEO && context.showSubtitles) add(TvFilesAction.Subtitles)
        add(TvFilesAction.OpenInVlc)
    }

/** Otherwise the reducer would refuse the Delete a permanent-deletion confirmation sends. */
internal fun FilesFolderState.permanentDeleteConfirmable(trashEnabled: Boolean?): Boolean =
    trashEnabled == false && operation.canStartOperation

internal class TvFilesActionHandlers(
    val onPlay: (FilesItem, TvPlayStart) -> Unit,
    val onPickSubtitles: (FilesItem, TvPlayStart) -> Unit,
    val onOpenInVlc: (FilesItem) -> Unit,
    val onSetWatched: (FilesItem, Boolean) -> Unit,
    val onSetPinned: (FilesItem, Boolean) -> Unit,
)

private fun TvFilesActionHandlers.run(action: TvFilesAction, item: FilesItem, playFrom: TvPlayStart) {
    when (action) {
        TvFilesAction.Resume -> onPlay(item, TvPlayStart.Resume)
        TvFilesAction.Play -> onPlay(item, TvPlayStart.Ask)
        TvFilesAction.StartOver -> onPlay(item, TvPlayStart.StartOver)
        TvFilesAction.Subtitles -> onPickSubtitles(item, playFrom)
        TvFilesAction.OpenInVlc -> onOpenInVlc(item)
        is TvFilesAction.SetWatched -> onSetWatched(item, action.watched)
        is TvFilesAction.SetPinned -> onSetPinned(item, action.pinned)
        is TvFilesAction.Delete -> Unit
    }
}

@Composable
internal fun TvFilesActionsMenu(
    item: FilesItem,
    folder: FilesFolderState,
    context: TvFilesActionContext,
    confirmingPermanentDelete: Boolean,
    onConfirmPermanentDelete: () -> Unit,
    trashPressed: Boolean,
    onTrashPressedChange: (Boolean) -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    handlers: TvFilesActionHandlers,
    onClose: () -> Unit,
) {
    val trashEnabled = context.trashEnabled
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
        return
    }
    val playFrom = if (item.resumePosition(context.resumePlayback) != null) TvPlayStart.Resume else TvPlayStart.Ask
    TvFilesActionsSheet(
        item = item,
        actions = item.tvActions(context.copy(canDelete = canStartOperation)),
        onAction = { action ->
            if (action is TvFilesAction.Delete) {
                if (action.trash) onTrashPressedChange(true) else onConfirmPermanentDelete()
            } else {
                onClose()
                handlers.run(action, item, playFrom)
            }
        },
        onDismiss = onClose,
    )
}

@Composable
internal fun TvFilesActionsSheet(
    item: FilesItem,
    actions: List<TvFilesAction>,
    onAction: (TvFilesAction) -> Unit,
    onDismiss: () -> Unit,
) {
    TvSideSheet(onDismiss = onDismiss, returnFocus = null, scrim = FILES_SHEET_SCRIM) { focus ->
        TvFilesActionsHeader(item)
        val tiles = actions.filterIsInstance<TvFilesAction.Tile>()
        val rows = actions.filterNot { it is TvFilesAction.Tile || it is TvFilesAction.Delete }
        val delete = actions.filterIsInstance<TvFilesAction.Delete>().firstOrNull()
        TvSheetRows {
            if (tiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = TILES_BOTTOM),
                    horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                ) {
                    tiles.forEachIndexed { index, tile ->
                        TvFilesActionTile(
                            label = tile.label(),
                            icon = tile.icon(),
                            onClick = { onAction(tile) },
                            modifier = Modifier.focusRequesterIf(index == 0, focus),
                        )
                    }
                }
            }
            rows.forEachIndexed { index, action ->
                TvSheetOption(
                    label = action.label(),
                    detail = null,
                    selected = false,
                    onClick = { onAction(action) },
                    leadingIcon = action.icon(),
                    role = Role.Button,
                    modifier = Modifier.focusRequesterIf(tiles.isEmpty() && index == 0, focus),
                )
            }
            if (delete != null) {
                if (tiles.isNotEmpty() || rows.isNotEmpty()) {
                    Spacer(
                        Modifier
                            .padding(horizontal = DIVIDER_INSET, vertical = DIVIDER_GAP)
                            .fillMaxWidth()
                            .height(DIVIDER)
                            .background(MaterialTheme.colorScheme.borderVariant),
                    )
                }
                TvSheetOption(
                    label = delete.label(),
                    detail = null,
                    selected = false,
                    onClick = { onAction(delete) },
                    leadingIcon = delete.icon(),
                    role = Role.Button,
                    danger = true,
                    modifier = Modifier.focusRequesterIf(tiles.isEmpty() && rows.isEmpty(), focus),
                )
            }
        }
    }
}

@Composable
private fun TvFilesActionsHeader(item: FilesItem) {
    Row(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = HEADER_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(HEADER_GAP),
    ) {
        Icon(
            painter = painterResource(fileTypeIconRes(item.type)),
            contentDescription = null,
            tint = tvAccent(focused = false),
            modifier = Modifier
                .padding(top = HEADER_GLYPH_TOP)
                .size(HEADER_GLYPH),
        )
        Column {
            Text(
                text = item.name,
                style = HEADER_NAME,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = HEADER_NAME_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = headerMeta(item),
                style = PutioTypeScale.tvMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = HEADER_META_GAP),
            )
        }
    }
}

@Composable
private fun headerMeta(item: FilesItem): AnnotatedString {
    val context = LocalContext.current
    val date = remember(item.createdAt, context) { item.createdAt.toDisplayDate(context) }
    val size = if (item.isFolder) null else formatFileSize(item.sizeBytes)
    return buildAnnotatedString {
        size?.let { append(it.asMonoRun()) }
        if (size != null && date != null) append(" · ")
        date?.let { append(it) }
    }
}

@Composable
private fun RowScope.TvFilesActionTile(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .weight(1f)
            .height(TILE_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PutioRadius.largeIncreased)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PutioDesignTokens.componentBgActive,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(TILE_HEIGHT)
                .padding(bottom = TILE_LIFT),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TILE_ICON_GAP, Alignment.CenterVertically),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(TILE_ICON))
            Text(label, style = TILE_LABEL, maxLines = 1)
        }
    }
}

@Composable
internal fun TvFilesAction.label(): String =
    stringResource(
        when (this) {
            TvFilesAction.Resume -> R.string.tv_files_action_resume
            TvFilesAction.Play -> R.string.tv_files_detail_play
            TvFilesAction.StartOver -> R.string.tv_files_detail_start_over
            TvFilesAction.Subtitles -> R.string.tv_player_subtitles
            TvFilesAction.OpenInVlc -> R.string.tv_files_action_vlc
            is TvFilesAction.SetWatched ->
                if (watched) R.string.tv_files_action_mark_watched else R.string.tv_files_action_mark_unwatched
            is TvFilesAction.SetPinned -> if (pinned) R.string.tv_files_action_pin else R.string.tv_files_action_unpin
            is TvFilesAction.Delete -> if (trash) R.string.tv_files_action_trash else R.string.tv_files_action_delete
        },
    )

@DrawableRes
private fun TvFilesAction.icon(): Int =
    when (this) {
        TvFilesAction.Resume, TvFilesAction.Play -> DesignR.drawable.ic_ph_play_fill
        TvFilesAction.StartOver -> R.drawable.ic_ph_arrow_counter_clockwise
        TvFilesAction.Subtitles -> R.drawable.ic_ph_subtitles
        TvFilesAction.OpenInVlc -> R.drawable.ic_ph_traffic_cone
        is TvFilesAction.SetWatched -> if (watched) R.drawable.ic_ph_eye else R.drawable.ic_ph_eye_slash
        is TvFilesAction.SetPinned -> if (pinned) R.drawable.ic_ph_push_pin else R.drawable.ic_ph_push_pin_slash
        is TvFilesAction.Delete -> R.drawable.ic_ph_trash
    }

@Composable
internal fun TvFilesDeleteDialog(
    item: FilesItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TvConfirmDialog(
        title = stringResource(R.string.tv_files_delete_title),
        message = stringResource(R.string.tv_files_delete_message, item.name),
        confirmLabel = stringResource(R.string.tv_files_delete_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        balanceMessage = true,
    )
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

private val HEADER_GAP = 14.dp
private val HEADER_GLYPH = 20.dp
private val HEADER_GLYPH_TOP = 2.dp
private val HEADER_BOTTOM = 15.5.dp
private val HEADER_META_GAP = 4.5.dp
private const val HEADER_NAME_LINES = 4
private val HEADER_NAME =
    PutioTypeScale.tvBody.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
private val TILE_HEIGHT = 76.dp
private val TILE_GAP = 8.dp
private val TILES_BOTTOM = 8.dp
private val TILE_ICON = 22.dp
private val TILE_ICON_GAP = 8.5.dp
private val TILE_LIFT = 1.5.dp
private val TILE_LABEL = PutioTypeScale.tvBody.copy(fontWeight = FontWeight.Medium)
private val DIVIDER = 1.dp
private val DIVIDER_GAP = 6.dp
private val DIVIDER_INSET = 12.dp
