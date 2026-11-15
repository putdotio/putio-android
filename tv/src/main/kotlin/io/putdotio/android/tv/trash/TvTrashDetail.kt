package io.putdotio.android.tv.trash

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.putdotio.android.R
import io.putdotio.android.design.RowText
import io.putdotio.android.design.rememberRowText
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.trashDaysLeft
import io.putdotio.android.tv.TV_MONO_RUN
import io.putdotio.android.tv.TvDetailBody
import io.putdotio.android.tv.TvDetailDivider
import io.putdotio.android.tv.TvDetailFact
import io.putdotio.android.tv.TvDetailTile
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.monoNumber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay

internal data class TvTrashDetailActions(
    val canRestore: Boolean,
    val canDelete: Boolean,
    val onRestore: () -> Unit,
    val onDelete: () -> Unit,
    val showsFocus: Boolean,
    val restoreModifier: Modifier,
    val deleteModifier: Modifier,
)

internal data class TvTrashDetailText(val meta: AnnotatedString, val expires: AnnotatedString?) {
    companion object {
        val Pending: TvTrashDetailText = TvTrashDetailText(AnnotatedString(""), expires = null)
    }
}

@Composable
internal fun rememberTvTrashDetailText(items: List<TrashItem>): RowText<TrashItem, TvTrashDetailText> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val today = LocalTrashToday.current
    val currentToday by rememberUpdatedState(today)
    return rememberRowText(
        items,
        context,
        configuration,
        key = { it.id.value },
        placeholder = TvTrashDetailText.Pending,
        refresh = today,
    ) {
        it.detailText(context, currentToday)
    }
}

private fun TrashItem.detailText(context: Context, today: LocalDate): TvTrashDetailText {
    val deletedLabel = deletedAt?.let { context.getString(R.string.tv_trash_deleted_on, trashDate(context, it)) }
    val meta = buildAnnotatedString {
        withStyle(TV_MONO_RUN) { append(formatFileSize(sizeBytes)) }
        if (deletedLabel != null) append(" · $deletedLabel")
    }
    val expires = expirationDate?.trashDaysLeft(today)?.let { days ->
        if (days == 0L) {
            AnnotatedString(context.getString(R.string.tv_trash_expires_today))
        } else {
            trashCount(context.resources, R.plurals.tv_trash_expires_in, days.toInt())
        }
    }
    return TvTrashDetailText(meta, expires)
}

@Composable
internal fun ColumnScope.TvTrashDetail(
    item: TrashItem,
    text: RowText<TrashItem, TvTrashDetailText>,
    actions: TvTrashDetailActions,
) {
    val built = text[item]
    val expiresLabel = stringResource(R.string.tv_trash_expires)
    val expires = built.expires?.let { TvDetailFact(expiresLabel, it.text, styled = it) }
    TvDetailBody(
        tile = { TvDetailTile(glyph = fileTypeIconRes(item.type)) },
        title = item.name,
        meta = built.meta,
        facts = listOfNotNull(expires),
        rows = { firstRow ->
            TvSheetOption(
                label = stringResource(R.string.tv_trash_restore),
                detail = null,
                selected = false,
                onClick = actions.onRestore,
                leadingIcon = R.drawable.ic_ph_arrow_u_up_left,
                role = Role.Button,
                enabled = actions.canRestore,
                showsFocus = actions.showsFocus,
                modifier = firstRow.then(actions.restoreModifier),
            )
            TvDetailDivider()
            TvSheetOption(
                label = stringResource(R.string.tv_trash_delete),
                detail = null,
                selected = false,
                onClick = actions.onDelete,
                leadingIcon = R.drawable.ic_ph_trash,
                role = Role.Button,
                enabled = actions.canDelete,
                danger = true,
                showsFocus = actions.showsFocus,
                modifier = actions.deleteModifier,
            )
        },
    )
}

internal val LocalTrashToday = compositionLocalOf<LocalDate> { error("LocalTrashToday is not provided") }

@Composable
internal fun rememberTrashToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        while (true) {
            val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
            delay((midnight.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L) + MIDNIGHT_SLACK_MILLIS)
            today = LocalDate.now()
        }
    }
    return today
}

internal fun trashCount(resources: Resources, @PluralsRes id: Int, count: Int): AnnotatedString =
    monoNumber(resources.getQuantityString(id, count, count), count.toString())

private fun trashDate(context: Context, value: String): String {
    val millis = parsePutioTimestamp(value)?.toEpochMilli()
        ?: try {
            LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            return value
        }
    return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
}

private const val MIDNIGHT_SLACK_MILLIS = 1_000L
