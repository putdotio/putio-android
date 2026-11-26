package io.putdotio.android.widgets

import androidx.annotation.StringRes
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.statusLabelRes
import kotlin.math.roundToInt

internal sealed interface TransfersWidgetContent {
    data object SignedOut : TransfersWidgetContent

    data object Loading : TransfersWidgetContent

    data object Unavailable : TransfersWidgetContent

    data class Ready(
        val rows: List<TransfersWidgetRow>,
        val more: Int,
        val updatedAtMillis: Long,
    ) : TransfersWidgetContent
}

internal data class TransfersWidgetRow(
    val name: String,
    @StringRes val status: Int,
    val percent: Int?,
    val seeding: Boolean = false,
) {
    override fun toString(): String =
        "TransfersWidgetRow(<redacted>, status=$status, percent=$percent, seeding=$seeding)"
}

internal const val TRANSFERS_WIDGET_ROWS = 3

internal fun List<TransferItem>.toTransfersWidgetContent(nowMillis: Long): TransfersWidgetContent.Ready {
    val active = filterNot { it.status.isTerminal }
    return TransfersWidgetContent.Ready(
        rows = active.take(TRANSFERS_WIDGET_ROWS).map { item ->
            TransfersWidgetRow(
                name = item.name,
                status = item.statusLabelRes(),
                percent = item.percentDone?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(0, PERCENT),
                seeding = item.status == AppTransferStatus.Seeding,
            )
        },
        more = (active.size - TRANSFERS_WIDGET_ROWS).coerceAtLeast(0),
        updatedAtMillis = nowMillis,
    )
}

private const val PERCENT = 100
