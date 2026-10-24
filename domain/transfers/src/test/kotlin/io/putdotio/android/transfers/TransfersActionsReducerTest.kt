package io.putdotio.android.transfers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Transfer actions that act on one finished transfer or its folder. */
class TransfersActionsReducerTest {
    @Test
    fun removeTakesOnlyACompletedTransferOffTheListAndOnlyWhenPutioDeletedIt() {
        val rows = listOf(item(1L), item(2L, AppTransferStatus.Failed), item(3L, AppTransferStatus.Completed))
        val loaded = TransfersReducer.start().complete(TransfersPage(rows, null))
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Remove(TransferId(1L))).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Remove(TransferId(2L))).consumed)

        val remove = TransfersReducer.reduce(loaded, TransfersEvent.Remove(TransferId(3L)))
        val mutate = remove.effect as TransfersEffect.Mutate
        assertEquals(TransferAction.Remove(TransferId(3L)), mutate.action)
        val kept = TransfersReducer.reduce(remove.state, TransfersEvent.MutationSucceeded(mutate.requestId))
        assertEquals(listOf(1L, 2L, 3L), (kept.state.content as TransfersContent.Ready).items.map { it.id.value })

        val removed = TransfersReducer.reduce(
            remove.state,
            TransfersEvent.MutationSucceeded(mutate.requestId, affectedIds = setOf(TransferId(3L))),
        )
        assertEquals(listOf(1L, 2L), (removed.state.content as TransfersContent.Ready).items.map { it.id.value })
        assertEquals(TransferMutation.Idle, removed.state.mutation)
    }

    @Test
    fun openFolderResolvesTheSaveFolderAndNeedsOne() {
        val rows = listOf(item(1L).copy(saveParentId = 0L), item(2L).copy(saveParentId = 42L), item(3L))
        val loaded = TransfersReducer.start().complete(TransfersPage(rows, null))

        assertEquals(
            TransferNavigation.Resolving(TransferFileId(0L), TransfersRequestId(2L)),
            TransfersReducer.reduce(loaded, TransfersEvent.OpenFolder(TransferId(1L))).state.navigation,
        )
        val opening = TransfersReducer.reduce(loaded, TransfersEvent.OpenFolder(TransferId(2L)))
        assertEquals(
            TransferNavigation.Resolving(TransferFileId(42L), TransfersRequestId(2L)),
            opening.state.navigation,
        )
        assertFalse(TransfersReducer.reduce(opening.state, TransfersEvent.OpenFolder(TransferId(1L))).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.OpenFolder(TransferId(3L))).consumed)
    }

    private fun TransfersTransition.complete(page: TransfersPage): TransfersState =
        TransfersReducer.reduce(
            state,
            TransfersEvent.ListSucceeded((effect as TransfersEffect.Load).requestId, page),
        ).state

    private fun item(id: Long, status: AppTransferStatus = AppTransferStatus.Downloading) = TransferItem(
        id = TransferId(id),
        name = "transfer-$id",
        status = status,
        fileId = null,
        sizeBytes = 100.0,
        percentDone = 40.0,
        downloadSpeedBytesPerSecond = 2.0,
        uploadSpeedBytesPerSecond = 1.0,
        estimatedSecondsRemaining = 30.0,
        availability = 1.0,
        errorMessage = null,
        createdAt = "2026-08-30T00:00:00Z",
        userFileExists = null,
    )
}
