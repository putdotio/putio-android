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
    var pending = itemIds
    var round = 0
    while (pending.isNotEmpty() && round < RESTORE_READBACK_ROUNDS) {
        if (round > 0) delay(RESTORE_READBACK_DELAY_MS)
        round += 1
        val still = mutableListOf<FilesItemId>()
        for (id in pending) {
            when (val read = resolveItem(id)) {
                is PutioResult.Success -> if (read.value.id != id) still += id
                is PutioResult.Failure -> {
                    val rejected = read.failure as? PutioFailure.AuthenticationRequired
                    if (rejected != null) return PutioResult.Failure(rejected)
                    still += id
                }
            }
        }
        pending = still
    }
    return PutioResult.Success(Unit)
}

private const val RESTORE_READBACK_ROUNDS = 5
private const val RESTORE_READBACK_DELAY_MS = 400L
