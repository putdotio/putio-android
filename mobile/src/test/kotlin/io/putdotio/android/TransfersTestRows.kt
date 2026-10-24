package io.putdotio.android

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferAction
import io.putdotio.android.transfers.TransferAddRequest
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferSubmission
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersRefresh
import io.putdotio.android.transfers.TransfersRequestId
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.transfers.copyForTest
import io.putdotio.android.transfers.transfersState

/** The Add transfer FAB, named through its semantics, or the empty state's button. */
internal val AddTransferAction = hasContentDescription("Add transfer") or hasText("Add transfer")

internal fun transfersTestState(
    content: TransfersContent,
    refresh: TransfersRefresh = TransfersRefresh.Idle,
): TransfersState = transfersState(content = content, refresh = refresh)

internal fun linksAddRequest(input: String) =
    TransferAddRequest.Links(requireNotNull(TransferSubmission.parseAll(input)))

internal fun TransfersState.withRunningLinkAdd(): TransfersState =
    copyForTest(
        mutation =
            TransferMutation.Running(
                TransferAction.Add(linksAddRequest("https://example.com/file")),
                TransfersRequestId(9L),
            ),
    )

internal fun transferRow(
    id: Long,
    status: AppTransferStatus,
    fileId: Long? = null,
    userFileExists: Boolean? = null,
): TransferItem =
    TransferItem(
        id = TransferId(id),
        name = "transfer-$id",
        status = status,
        fileId = fileId?.let(::TransferFileId),
        sizeBytes = 1_048_576.0,
        percentDone = null,
        downloadSpeedBytesPerSecond = null,
        uploadSpeedBytesPerSecond = null,
        estimatedSecondsRemaining = null,
        availability = null,
        errorMessage = null,
        createdAt = "2026-08-30T00:00:00Z",
        userFileExists = userFileExists,
    )
