package io.putdotio.android.tv.history

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.tv.TV_MONO_RUN
import io.putdotio.android.tv.TvDetailBody
import io.putdotio.android.tv.TvDetailButton
import io.putdotio.android.tv.TvDetailFact
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

@Composable
internal fun ColumnScope.TvHistoryDetail(
    item: HistoryItem,
    files: TvHistoryFiles,
    today: LocalDate,
    zone: ZoneId,
    openFocus: FocusRequester,
    onOpenFolder: (HistoryFileId) -> Unit,
    onAuthenticationRequired: suspend () -> Unit,
) {
    val fileId = item.kind.navigableFileId()
    val rejected by rememberUpdatedState(onAuthenticationRequired)
    val file by produceState(fileId?.let(files::known), fileId, files) {
        value = fileId?.let(files::known)
        if (fileId == null || value != null) return@produceState
        delay(LOOKUP_DELAY_MILLIS)
        when (val result = files.load(fileId)) {
            is PutioResult.Success -> value = result.value
            is PutioResult.Failure -> if (result.failure is PutioFailure.AuthenticationRequired) rejected()
            null -> Unit
        }
    }
    TvDetailBody(title = item.title(), meta = detailMeta(item, today, zone), facts = detailFacts(file)) {
        if (fileId != null) {
            TvDetailButton(
                label = AnnotatedString(stringResource(R.string.tv_history_open_folder)),
                icon = R.drawable.ic_ph_folder_open,
                onClick = { onOpenFolder(fileId) },
                modifier = Modifier.focusRequester(openFocus),
            )
        }
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
private fun detailFacts(file: TvHistoryFile?): List<TvDetailFact> {
    if (file == null) return emptyList()
    val savedTo = when (val folder = file.savedTo) {
        TvSavedTo.YourFiles -> stringResource(R.string.tv_files_title)
        is TvSavedTo.Folder -> folder.name
        null -> null
    }
    return listOfNotNull(
        TvDetailFact(stringResource(R.string.tv_history_detail_size), formatFileSize(file.sizeBytes), mono = true),
        savedTo?.let { TvDetailFact(stringResource(R.string.tv_history_detail_saved_to), it) },
    )
}

private const val META_SEPARATOR = " · "
private const val DAY_TIME_SEPARATOR = ", "
private const val LOOKUP_DELAY_MILLIS = 300L
