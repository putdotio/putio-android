package io.putdotio.android.widgets

import android.content.Context
import android.text.format.DateFormat
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
    val footer = (content as? TransfersWidgetContent.Ready)?.footer(context).orEmpty()
    views.setTextViewText(R.id.widget_transfers_footer, footer)
    views.setViewVisibility(R.id.widget_transfers_footer, if (footer.isEmpty()) View.GONE else View.VISIBLE)
    return views
}

private fun RemoteViews.showRow(context: Context, ids: RowViews, row: TransfersWidgetRow?) {
    setViewVisibility(ids.row, if (row == null) View.GONE else View.VISIBLE)
    setTextViewText(ids.name, row?.name.orEmpty())
    val status = row?.let { context.getString(it.status) }.orEmpty()
    setTextViewText(
        ids.status,
        row?.percent?.let { context.getString(R.string.mobile_widget_transfers_status_percent, status, it) } ?: status,
    )
    setProgressBar(ids.progress, PROGRESS_MAX, row?.percent ?: 0, row != null && row.percent == null)
}

private fun TransfersWidgetContent.Ready.footer(context: Context): String {
    val time = DateFormat.getTimeFormat(context).format(Date(updatedAtMillis))
    return if (more > 0) {
        context.resources.getQuantityString(R.plurals.mobile_widget_transfers_updated_more, more, time, more)
    } else {
        context.getString(R.string.mobile_widget_transfers_updated, time)
    }
}

private class RowViews(val row: Int, val name: Int, val status: Int, val progress: Int)

private val ROW_VIEWS = listOf(
    RowViews(
        R.id.widget_transfers_row_0,
        R.id.widget_transfers_row_0_name,
        R.id.widget_transfers_row_0_status,
        R.id.widget_transfers_row_0_progress,
    ),
    RowViews(
        R.id.widget_transfers_row_1,
        R.id.widget_transfers_row_1_name,
        R.id.widget_transfers_row_1_status,
        R.id.widget_transfers_row_1_progress,
    ),
    RowViews(
        R.id.widget_transfers_row_2,
        R.id.widget_transfers_row_2_name,
        R.id.widget_transfers_row_2_status,
        R.id.widget_transfers_row_2_progress,
    ),
).also { check(it.size == TRANSFERS_WIDGET_ROWS) }

private const val PROGRESS_MAX = 100
