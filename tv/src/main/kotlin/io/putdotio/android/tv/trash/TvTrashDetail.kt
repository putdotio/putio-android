package io.putdotio.android.tv.trash

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.putdotio.android.R
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.trashDaysLeft
import io.putdotio.android.tv.TV_MONO_RUN
import io.putdotio.android.tv.TvDetailBody
import io.putdotio.android.tv.TvDetailButton
import io.putdotio.android.tv.TvDetailFact
import io.putdotio.android.tv.monoNumber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The focused row's name, size and deletion date, then Restore and Delete permanently, then
 * when Trash drops it. Restore carries the focus look while the row is focused: Center on the
 * row restores.
 */
@Composable
internal fun ColumnScope.TvTrashDetail(
    item: TrashItem,
    actions: TvTrashDetailActions,
    restoreModifier: Modifier,
    deleteModifier: Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val deleted = remember(context, configuration, item.deletedAt) { item.deletedAt?.let { trashDate(context, it) } }
    val deletedLabel = deleted?.let { stringResource(R.string.tv_trash_deleted_on, it) }
    val meta = remember(item.sizeBytes, deletedLabel) {
        buildAnnotatedString {
            withStyle(TV_MONO_RUN) { append(formatFileSize(item.sizeBytes)) }
            if (deletedLabel != null) append(" · $deletedLabel")
        }
    }
    val daysLeft = rememberTrashDaysLeft(item)
    val expires = daysLeft?.let { days ->
        val styled = if (days == 0L) {
            AnnotatedString(stringResource(R.string.tv_trash_expires_today))
        } else {
            trashCount(R.plurals.tv_trash_expires_in, days.toInt())
        }
        TvDetailFact(stringResource(R.string.tv_trash_expires), styled.text, styled = styled)
    }
    TvDetailBody(title = item.name, meta = meta, facts = listOfNotNull(expires)) {
        TvDetailButton(
            label = AnnotatedString(stringResource(R.string.tv_trash_restore)),
            icon = R.drawable.ic_ph_arrow_u_up_left,
            onClick = actions.onRestore,
            modifier = restoreModifier,
            primary = true,
            detailHasFocus = actions.detailHasFocus,
            enabled = actions.canRestore,
            looksFocused = actions.rowHasFocus && actions.canRestore,
        )
        TvDetailButton(
            label = AnnotatedString(stringResource(R.string.tv_trash_delete)),
            icon = R.drawable.ic_ph_trash,
            onClick = actions.onDelete,
            modifier = deleteModifier,
            enabled = actions.canDelete,
            danger = true,
        )
    }
}

@Composable
internal fun rememberTrashDaysLeft(item: TrashItem): Long? =
    remember(item.expirationDate) { item.expirationDate?.trashDaysLeft(LocalDate.now()) }

@Composable
internal fun trashCount(@PluralsRes id: Int, count: Int): AnnotatedString {
    val number = count.toString()
    return monoNumber(pluralStringResource(id, count, count), number)
}

/** "Oct 19" this year, "Oct 19, 2025" before it, from a stamp or a plain date; the raw value otherwise. */
private fun trashDate(context: Context, value: String): String {
    val millis = parsePutioTimestamp(value)?.toEpochMilli()
        ?: try {
            LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            return value
        }
    return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
}
