package io.putdotio.android.trash

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItemId
import kotlinx.coroutines.delay

public suspend fun TrashRepository.undoTrashMove(itemIds: List<FilesItemId>): PutioResult<Unit> {
    val restored = if (itemIds.size == 1) {
        restore(itemIds.single())
    } else {
        restoreAll(TrashBulkSelection(cursor = null, itemIds = itemIds))
    }
    if (restored is PutioResult.Failure) return restored
    val rejected = awaitRestored(itemIds)
    return if (rejected == null) PutioResult.Success(Unit) else PutioResult.Failure(rejected)
}

private suspend fun TrashRepository.awaitRestored(itemIds: List<FilesItemId>): PutioFailure.AuthenticationRequired? {
    var pending = itemIds
    var rejected: PutioFailure.AuthenticationRequired? = null
    var round = 0
    while (pending.isNotEmpty() && rejected == null && round < RESTORE_READBACK_ROUNDS) {
        if (round > 0) delay(RESTORE_READBACK_DELAY_MS)
        round += 1
        pending = pending.filterNot { id ->
            rejected == null && when (val read = resolveItem(id)) {
                is PutioResult.Success -> read.value.id == id
                is PutioResult.Failure -> {
                    rejected = read.failure as? PutioFailure.AuthenticationRequired
                    false
                }
            }
        }
    }
    return rejected
}

private const val RESTORE_READBACK_ROUNDS = 5
private const val RESTORE_READBACK_DELAY_MS = 400L
