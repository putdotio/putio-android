package io.putdotio.android.tv.history

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.putdotio.android.R
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
import io.putdotio.android.tv.files.resumePosition
import io.putdotio.android.tv.files.tvPrimaryAction
import java.time.LocalDate
import java.time.ZoneId
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

@Composable
internal fun ColumnScope.TvHistoryDetail(
    item: HistoryItem,
    lookup: TvHistoryLookup?,
    today: LocalDate,
    zone: ZoneId,
    openFocus: FocusRequester,
    actions: TvHistoryDetailActions,
) {
    val fileId = item.kind.navigableFileId()
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
        meta = detailMeta(item, today, zone),
        facts = detailFacts(lookup),
        rows = when {
            found != null -> { firstRow -> HistoryRows(found.item, fileId, firstRow, openFocus, actions) }
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
    fileId: HistoryFileId?,
    modifier: Modifier,
    openFocus: FocusRequester,
    actions: TvHistoryDetailActions,
) {
    val open = { fileId?.let(actions.onGoToFolder) ?: Unit }
    val primary = tvPrimaryAction(
        item = item,
        resumeAt = item.resumePosition(actions.playback.resumePlayback),
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
private fun detailMeta(item: HistoryItem, today: LocalDate, zone: ZoneId): AnnotatedString {
    val kind = item.kind.kindLabel()?.let { stringResource(it) }
    val day = item.day(zone)
    val dayLabel = day.label(today)
    val time = item.timeLabel(zone)
    return buildAnnotatedString {
        if (kind != null) {
            append(kind)
            append(META_SEPARATOR)
        }
        append(dayLabel)
        if (day.date != null) {
            append(DAY_TIME_SEPARATOR)
            withStyle(TV_MONO_RUN) { append(time) }
        }
    }
}

@Composable
private fun detailFacts(lookup: TvHistoryLookup?): List<TvDetailFact> {
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
            formatFileSize(file.sizeBytes),
            mono = true,
        ),
        savedTo?.let { TvDetailFact(stringResource(R.string.tv_history_detail_saved_to), it) },
    )
}

private const val META_SEPARATOR = " · "
private const val DAY_TIME_SEPARATOR = ", "
private const val LOOKUP_DELAY_MILLIS = 300L
