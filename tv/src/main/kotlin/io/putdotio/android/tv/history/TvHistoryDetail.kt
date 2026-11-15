package io.putdotio.android.tv.history

import android.content.res.Resources
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.putdotio.android.R
import io.putdotio.android.design.rememberRowText
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.navigableFileId
import io.putdotio.android.tv.TV_MONO_RUN
import io.putdotio.android.tv.TvDetailBody
import io.putdotio.android.tv.TvDetailDivider
import io.putdotio.android.tv.TvDetailFact
import io.putdotio.android.tv.TvDetailPrimarySkeleton
import io.putdotio.android.tv.TvDetailTile
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.files.TvFilesAction
import io.putdotio.android.tv.files.TvItemThumbnails
import io.putdotio.android.tv.files.TvItemTile
import io.putdotio.android.tv.files.TvPrimaryRow
import io.putdotio.android.tv.files.icon
import io.putdotio.android.tv.files.label
import io.putdotio.android.tv.files.resumeLabel
import io.putdotio.android.tv.files.resumePosition
import io.putdotio.android.tv.files.shownResume
import io.putdotio.android.tv.files.tvPrimaryAction
import kotlinx.coroutines.delay

@Composable
internal fun rememberTvHistoryLookup(
    fileId: HistoryFileId?,
    files: TvHistoryFiles,
    onAuthenticationRequired: suspend () -> Unit,
): TvHistoryLookup? {
    val rejected by rememberUpdatedState(onAuthenticationRequired)
    val lookup by produceState(fileId?.let(files::known), fileId, files, files.changesRead) {
        value = fileId?.let(files::known)
        if (fileId == null || value != null) return@produceState
        value = TvHistoryLookup.Pending
        delay(LOOKUP_DELAY_MILLIS)
        value = files.lookup(fileId) { rejected() }
    }
    return lookup
}

internal class TvHistoryFileText(val size: String, val resume: AnnotatedString?)

internal class TvHistoryShown(val lookup: TvHistoryLookup?, val text: TvHistoryFileText?)

@Composable
internal fun rememberTvHistoryShown(lookup: TvHistoryLookup?): TvHistoryShown {
    val resources = LocalResources.current
    val file = (lookup as? TvHistoryLookup.Found)?.file
    val built = rememberRowText<TvHistoryFile, TvHistoryFileText?>(
        listOfNotNull(file),
        resources,
        key = { it.item.id },
        placeholder = null,
    ) { it.text(resources) }
    val text = file?.let { built[it] }
    val waits = lookup is TvHistoryLookup.Found && text == null
    return TvHistoryShown(if (waits) TvHistoryLookup.Pending else lookup, text)
}

private fun TvHistoryFile.text(resources: Resources): TvHistoryFileText =
    TvHistoryFileText(
        size = formatFileSize(sizeBytes),
        resume = item.resumePosition(resumePlayback = true)?.let { resumeLabel(resources, it) },
    )

@Composable
internal fun ColumnScope.TvHistoryDetail(
    item: HistoryItem,
    shown: TvHistoryShown,
    stamp: TvHistoryStamp?,
    openFocus: FocusRequester,
    actions: TvHistoryDetailActions,
) {
    val fileId = item.kind.navigableFileId()
    val lookup = shown.lookup
    val fileText = shown.text
    val found = (lookup as? TvHistoryLookup.Found)?.file
    TvDetailBody(
        tile = {
            when {
                found != null -> TvItemTile(found.item, actions.playback.thumbnails)
                lookup == TvHistoryLookup.Pending -> TvDetailTile(glyph = null)
                else -> TvDetailTile(glyph = item.kind.glyph())
            }
        },
        title = found?.item?.name ?: item.title(),
        key = item.id,
        meta = detailMeta(item, stamp),
        facts = detailFacts(lookup, fileText),
        rows = when {
            found != null -> { firstRow ->
                HistoryRows(found.item, fileText?.resume, fileId, firstRow, openFocus, actions)
            }
            lookup == TvHistoryLookup.Pending -> { _ -> TvDetailPrimarySkeleton() }
            lookup == TvHistoryLookup.Unknown && fileId != null -> { firstRow ->
                TvSheetOption(
                    label = stringResource(R.string.tv_history_go_to_folder),
                    detail = null,
                    selected = false,
                    onClick = { actions.onGoToFolder(fileId) },
                    leadingIcon = R.drawable.ic_ph_folder_open,
                    role = Role.Button,
                    modifier = firstRow.focusRequester(openFocus),
                )
            }
            else -> null
        },
    )
}

@Composable
private fun HistoryRows(
    item: FilesItem,
    resume: AnnotatedString?,
    fileId: HistoryFileId?,
    modifier: Modifier,
    openFocus: FocusRequester,
    actions: TvHistoryDetailActions,
) {
    val open = { fileId?.let(actions.onGoToFolder) ?: Unit }
    val primary = tvPrimaryAction(
        item = item,
        resume = item.shownResume(actions.playback.resumePlayback, resume),
        onOpenFolder = open,
        onPlay = { actions.playback.onPlay(item) },
        onOpenFile = open,
    )
    TvPrimaryRow(primary, modifier.focusRequester(openFocus))
    val rows = buildList {
        if (fileId != null && !item.isFolder) add(TvFilesAction.GoToFolder)
        addAll(actions.secondaryOf(item).filter { it is TvFilesAction.SetPinned || it is TvFilesAction.Delete })
    }
    rows.forEach { action ->
        val delete = action is TvFilesAction.Delete
        if (delete) TvDetailDivider()
        TvSheetOption(
            label = action.label(),
            detail = null,
            selected = false,
            onClick = {
                if (action == TvFilesAction.GoToFolder) {
                    fileId?.let(actions.onGoToFolder)
                } else {
                    actions.onSecondary(item, action)
                }
            },
            leadingIcon = action.icon(),
            role = Role.Button,
            enabled = !delete || !actions.editing,
            danger = delete,
        )
    }
}

@Composable
private fun detailMeta(item: HistoryItem, stamp: TvHistoryStamp?): AnnotatedString {
    val kind = item.kind.kindLabel()?.let { stringResource(it) }
    return buildAnnotatedString {
        if (kind != null) append(kind)
        if (stamp == null) return@buildAnnotatedString
        if (kind != null) append(META_SEPARATOR)
        append(stamp.day)
        stamp.time?.let {
            append(DAY_TIME_SEPARATOR)
            withStyle(TV_MONO_RUN) { append(it) }
        }
    }
}

@Composable
private fun detailFacts(lookup: TvHistoryLookup?, text: TvHistoryFileText?): List<TvDetailFact> {
    val file = (lookup as? TvHistoryLookup.Found)?.file
    if (file == null) {
        return listOfNotNull(
            TvDetailFact(
                stringResource(R.string.tv_history_detail_file),
                stringResource(R.string.tv_history_file_gone),
            ).takeIf { lookup == TvHistoryLookup.Gone },
        )
    }
    val savedTo = when (val folder = file.savedTo) {
        TvSavedTo.YourFiles -> stringResource(R.string.tv_files_title)
        is TvSavedTo.Folder -> folder.name
        null -> null
    }
    return listOfNotNull(
        TvDetailFact(
            stringResource(R.string.tv_history_detail_size),
            text?.size.orEmpty(),
            mono = true,
        ),
        savedTo?.let { TvDetailFact(stringResource(R.string.tv_history_detail_saved_to), it) },
    )
}

private const val META_SEPARATOR = " · "
private const val DAY_TIME_SEPARATOR = ", "
private const val LOOKUP_DELAY_MILLIS = 300L
