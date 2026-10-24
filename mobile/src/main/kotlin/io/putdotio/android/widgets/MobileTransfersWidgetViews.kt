package io.putdotio.android.widgets

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.RemoteViews
import io.putdotio.android.MobileDeepLink
import io.putdotio.android.R
import io.putdotio.android.pendingIntent
import java.util.Date

/**
 * The widget's views for [content]. Every text is set on every push, and a hidden row's name is
 * emptied: a launcher reapplies new views onto the old ones, so a row left untouched would keep
 * the previous account's name. Intents carry token-free product links only.
 */
internal fun transfersWidgetViews(context: Context, content: TransfersWidgetContent): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_transfers)
    views.setOnClickPendingIntent(android.R.id.background, MobileDeepLink.Transfers.pendingIntent(context))
    views.setOnClickPendingIntent(R.id.widget_transfers_refresh, MobileWidgetActionReceiver.refresh(context))
    views.setViewVisibility(
        R.id.widget_transfers_refresh,
        if (content == TransfersWidgetContent.SignedOut) View.GONE else View.VISIBLE,
    )
    val message = when (content) {
        TransfersWidgetContent.SignedOut -> R.string.mobile_widget_transfers_signed_out
        TransfersWidgetContent.Loading -> R.string.mobile_widget_transfers_loading
        TransfersWidgetContent.Unavailable -> R.string.mobile_widget_transfers_unavailable
        is TransfersWidgetContent.Ready -> R.string.mobile_widget_transfers_empty.takeIf { content.rows.isEmpty() }
    }
    views.setTextViewText(R.id.widget_transfers_message, message?.let(context::getString).orEmpty())
    views.setViewVisibility(R.id.widget_transfers_message, if (message == null) View.GONE else View.VISIBLE)
    val rows = (content as? TransfersWidgetContent.Ready)?.rows.orEmpty()
    ROW_VIEWS.forEachIndexed { index, ids -> views.showRow(context, ids, rows.getOrNull(index)) }
    val ready = content as? TransfersWidgetContent.Ready
    views.setTextViewText(R.id.widget_transfers_footer, ready?.updated(context) ?: "")
    views.setTextViewText(R.id.widget_transfers_more, ready?.more(context) ?: "")
    views.setViewVisibility(R.id.widget_transfers_footer_row, if (ready == null) View.GONE else View.VISIBLE)
    return views
}

private fun RemoteViews.showRow(context: Context, ids: RowViews, row: TransfersWidgetRow?) {
    setViewVisibility(ids.row, if (row == null) View.GONE else View.VISIBLE)
    setTextViewText(ids.name, row?.name.orEmpty())
    setTextViewText(ids.status, row?.let { context.getString(it.status) }.orEmpty())
    setTextViewText(
        ids.percent,
        row?.percent?.let { context.getString(R.string.mobile_widget_transfers_percent, it) }.orEmpty(),
    )
    val seeding = row?.seeding == true
    setViewVisibility(ids.progress, if (seeding) View.GONE else View.VISIBLE)
    setViewVisibility(ids.seedingProgress, if (seeding) View.VISIBLE else View.GONE)
    setProgressBar(ids.progress, PROGRESS_MAX, row?.percent ?: 0, row != null && row.percent == null)
    setProgressBar(ids.seedingProgress, PROGRESS_MAX, row?.percent ?: PROGRESS_MAX, false)
}

/** "Updated 10:41", the time in mono. */
private fun TransfersWidgetContent.Ready.updated(context: Context): CharSequence {
    val time = DateFormat.getTimeFormat(context).format(Date(updatedAtMillis))
    return monoSlot(context.getString(R.string.mobile_widget_transfers_updated, MONO_SLOT), time)
}

/** "2 more running", the count in mono; nothing when every running transfer has a row. */
private fun TransfersWidgetContent.Ready.more(context: Context): CharSequence =
    if (more > 0) {
        val template = context.resources.getQuantityString(R.plurals.mobile_widget_transfers_more, more, MONO_SLOT)
        monoSlot(template, more.toString())
    } else {
        ""
    }

/** [template] with its one slot replaced by [value] in the monospace face; spans travel through RemoteViews. */
private fun monoSlot(template: String, value: String): CharSequence {
    val start = template.indexOf(MONO_SLOT)
    if (start < 0) return template
    return SpannableStringBuilder(template).apply {
        replace(start, start + MONO_SLOT.length, value)
        setSpan(TypefaceSpan(MONOSPACE), start, start + value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private class RowViews(
    val row: Int,
    val name: Int,
    val status: Int,
    val percent: Int,
    val progress: Int,
    val seedingProgress: Int,
)

private val ROW_VIEWS = listOf(
    RowViews(
        R.id.widget_transfers_row_0,
        R.id.widget_transfers_row_0_name,
        R.id.widget_transfers_row_0_status,
        R.id.widget_transfers_row_0_percent,
        R.id.widget_transfers_row_0_progress,
        R.id.widget_transfers_row_0_progress_seeding,
    ),
    RowViews(
        R.id.widget_transfers_row_1,
        R.id.widget_transfers_row_1_name,
        R.id.widget_transfers_row_1_status,
        R.id.widget_transfers_row_1_percent,
        R.id.widget_transfers_row_1_progress,
        R.id.widget_transfers_row_1_progress_seeding,
    ),
    RowViews(
        R.id.widget_transfers_row_2,
        R.id.widget_transfers_row_2_name,
        R.id.widget_transfers_row_2_status,
        R.id.widget_transfers_row_2_percent,
        R.id.widget_transfers_row_2_progress,
        R.id.widget_transfers_row_2_progress_seeding,
    ),
).also { check(it.size == TRANSFERS_WIDGET_ROWS) }

private const val PROGRESS_MAX = 100
private const val MONO_SLOT = "\u0001"
private const val MONOSPACE = "monospace"
