package io.putdotio.android.widgets

import androidx.annotation.StringRes
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.statusLabelRes
import kotlin.math.roundToInt

/** What the Transfers widget shows. Only [Ready] carries names, and only the signed-in session's. */
internal sealed interface TransfersWidgetContent {
    data object SignedOut : TransfersWidgetContent

    data object Loading : TransfersWidgetContent

    /** put.io could not be reached, or the session could not be confirmed, and nothing was shown yet. */
    data object Unavailable : TransfersWidgetContent

    data class Ready(
        val rows: List<TransfersWidgetRow>,
        /** Active transfers beyond [rows]. */
        val more: Int,
        val updatedAtMillis: Long,
    ) : TransfersWidgetContent
}

internal data class TransfersWidgetRow(
    val name: String,
    @StringRes val status: Int,
    /** Whole percent when put.io reports one; the bar is indeterminate otherwise. */
    val percent: Int?,
) {
    override fun toString(): String = "TransfersWidgetRow(<redacted>, status=$status, percent=$percent)"
}

/** The widget has room for this many rows at its default four-by-two size. */
internal const val TRANSFERS_WIDGET_ROWS = 3

/** Transfers still running, newest first as put.io lists them; finished and failed ones are left to the app. */
internal fun List<TransferItem>.toTransfersWidgetContent(nowMillis: Long): TransfersWidgetContent.Ready {
    val active = filterNot { it.status.isTerminal }
    return TransfersWidgetContent.Ready(
        rows = active.take(TRANSFERS_WIDGET_ROWS).map { item ->
            TransfersWidgetRow(
                name = item.name,
                status = item.statusLabelRes(),
                percent = item.percentDone?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(0, PERCENT),
            )
        },
        more = (active.size - TRANSFERS_WIDGET_ROWS).coerceAtLeast(0),
        updatedAtMillis = nowMillis,
    )
}

private const val PERCENT = 100
