package io.putdotio.android.transfers

import io.putdotio.sdk.files.FileUploadInput
import io.putdotio.sdk.files.FileUploadResult
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.transfers.TransferAddInput
import io.putdotio.sdk.transfers.TransfersAddManyResponse
import io.putdotio.sdk.transfers.TransfersCleanResponse
import io.putdotio.sdk.transfers.TransfersListQuery
import io.putdotio.sdk.transfers.TransfersListResponse

/** An [SdkTransfersRepository] over scripted SDK calls; a call the test leaves unscripted fails it. */
public fun scriptedSdkTransfersRepository(
    list: suspend (TransfersListQuery) -> TransfersListResponse = { error("Unexpected transfers list") },
    continueList: suspend (String, TransfersListQuery) -> TransfersListResponse = { _, _ ->
        error("Unexpected transfers page")
    },
    get: suspend (Long) -> Transfer = { error("Unexpected read of transfer $it") },
    add: suspend (TransferAddInput) -> Transfer = { error("Unexpected add") },
    addMany: suspend (List<TransferAddInput>) -> TransfersAddManyResponse = { error("Unexpected add") },
    upload: suspend (FileUploadInput) -> FileUploadResult = { error("Unexpected upload") },
    cancelTransfers: suspend (List<Long>) -> Unit = { error("Unexpected cancel") },
    retryTransfer: suspend (Long) -> Transfer = { error("Unexpected retry of transfer $it") },
    cleanTransfers: suspend (List<Long>) -> TransfersCleanResponse = { error("Unexpected clean") },
): SdkTransfersRepository =
    SdkTransfersRepository(
        reads = TransfersReadOperations(list, continueList, get),
        adds = TransfersAddOperations(add, addMany, upload),
        cancelTransfers = cancelTransfers,
        retryTransfer = retryTransfer,
        cleanTransfers = cleanTransfers,
    )
