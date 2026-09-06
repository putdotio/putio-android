package io.putdotio.android.files

import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesMoveReducerTest {
    private val item = file(7L)
    private val move = FilesBrowserEvent.Move(FilesFolder.Root.id, item.id, FilesItemId(9L))

    @Test
    fun guardsInvalidSelectionsAndDuplicateSubmissionWithoutReplacingSourceState() {
        val root = loadedRoot(listOf(item, file(0L), file(-1L)))
        val invalid = listOf(
            move.copy(itemId = FilesItemId(0L)), move.copy(itemId = FilesItemId(-1L)),
            move.copy(itemId = FilesItemId(99L)), move.copy(folderId = FilesItemId(8L)),
            move.copy(destinationId = FilesItemId(-1L)), move.copy(destinationId = FilesFolder.Root.id),
            move.copy(destinationId = item.id),
        )
        invalid.forEach { assertFalse(FilesBrowserReducer.reduce(root, it).consumed) }
        val moving = FilesBrowserReducer.reduce(root, move)
        assertEquals(item.id, (moving.effect as FilesBrowserEffect.Move).itemId)
        assertEquals(root.path, moving.state.path)
        assertFalse(FilesBrowserReducer.reduce(moving.state, move).consumed)
        assertFalse(FilesBrowserReducer.reduce(moving.state, FilesBrowserEvent.Refresh).consumed)
        assertFalse(FilesBrowserReducer.reduce(moving.state, FilesBrowserEvent.MutationSucceeded(
            checkNotNull(moving.effect).requestId,
        )).consumed)
    }

    @Test
    fun acknowledgementRequiresExactDestinationAndRetainsAllPerItemErrors() {
        val unexpectedError = FileMoveError("FUTURE_ERROR", 99L, null, 409)
        for (errors in listOf(emptyList(), listOf(unexpectedError))) {
            val checking = finishPost(FilesRepositoryResult.Success(errors))
            assertTrue(checking.effect is FilesBrowserEffect.CheckMove)
            assertEquals(listOf(item), checking.state.current.content.items())
            val reloading = check(checking, FilesRepositoryResult.Success(item.copy(parentId = move.destinationId)))
            val finished = reload(reloading, FilesPage(emptyList(), null))
            val outcome = checkNotNull(finished.current.moveOutcome)
            assertEquals(if (errors.isEmpty()) FilesMoveStatus.MOVED else FilesMoveStatus.REJECTED, outcome.status)
            assertEquals(errors, outcome.errors)
            assertEquals(FilesFolderOperation.Idle, finished.current.operation)
        }
        val anotherParent = check(finishPost(), FilesRepositoryResult.Success(item.copy(parentId = FilesItemId(12L))))
        assertEquals(
            FilesMoveStatus.STILL_PRESENT,
            reload(anotherParent, FilesPage(emptyList(), null)).current.moveOutcome?.status,
        )
    }

    @Test
    fun uncertainPostAndMissingOrMalformedReadbackOnlyRetryTheExactRead() {
        val originalFailure = failure("connection lost after submission")
        val checking = finishPost(FilesRepositoryResult.Failure(originalFailure))
        val invalidReads = listOf(
            FilesRepositoryResult.Failure(notFound()),
            FilesRepositoryResult.Failure(failure("offline read")),
            FilesRepositoryResult.Success(file(999L).copy(parentId = move.destinationId)),
            FilesRepositoryResult.Success(item.copy(parentId = null)),
        )
        invalidReads.forEach { result ->
            val failed = check(checking, result).state
            assertEquals(FilesMoveStatus.UNKNOWN, failed.current.moveOutcome?.status)
            assertSame(originalFailure, failed.current.moveOutcome?.failure)
            assertEquals(listOf(item), failed.current.content.items())
            assertFalse(FilesBrowserReducer.reduce(failed, move).consumed)
            val retry = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)
            assertEquals(item.id, (retry.effect as FilesBrowserEffect.CheckMove).itemId)
            val recovered = check(retry, FilesRepositoryResult.Success(item.copy(parentId = move.destinationId)))
            assertEquals(
                FilesMoveStatus.MOVED,
                reload(recovered, FilesPage(emptyList(), null)).current.moveOutcome?.status,
            )
        }
    }

    @Test
    fun failedReadPreservesReportedErrorsAndReloadRetryDoesNotRepeatPost() {
        val errors = listOf(FileMoveError("NAME_ALREADY_EXIST", item.id.value, item.name, 400))
        val checking = finishPost(FilesRepositoryResult.Success(errors))
        val failedRead = check(checking, FilesRepositoryResult.Failure(failure("offline"))).state
        assertEquals(FilesMoveStatus.REJECTED, failedRead.current.moveOutcome?.status)
        assertEquals(errors, failedRead.current.moveOutcome?.errors)
        val reloading = check(
            FilesBrowserReducer.reduce(failedRead, FilesBrowserEvent.Retry), FilesRepositoryResult.Success(item),
        )
        val failedReload = FilesBrowserReducer.reduce(reloading.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(reloading.effect).requestId, failure("reload offline"),
        )).state
        val retry = FilesBrowserReducer.reduce(failedReload, FilesBrowserEvent.Retry)
        assertTrue(retry.effect is FilesBrowserEffect.LoadFolder)
        assertEquals(errors, reload(retry, FilesPage(listOf(item), null)).current.moveOutcome?.errors)
        assertFalse(FilesBrowserReducer.reduce(retry.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(reloading.effect).requestId, FilesPage(emptyList(), null),
        )).consumed)
    }

    @Test
    fun pendingAndFailedMoveCannotLoseRecoveryThroughInternalOrExternalNavigation() {
        val source = file(3L)
        val opening = FilesBrowserReducer.reduce(loadedRoot(listOf(source)), FilesBrowserEvent.OpenFolder(source.id))
        val child = reload(opening, FilesPage(listOf(item.copy(parentId = source.id), file(8L)), null))
        val moving = FilesBrowserReducer.reduce(child, move.copy(folderId = source.id))
        val checking = FilesBrowserReducer.reduce(moving.state, FilesBrowserEvent.MoveFinished(
            checkNotNull(moving.effect).requestId, FilesRepositoryResult.Success(emptyList()),
        ))
        val failed = check(checking, FilesRepositoryResult.Failure(failure("offline"))).state
        for (state in listOf(moving.state, checking.state, failed)) {
            val navigation = listOf(
                FilesBrowserEvent.NavigateBack, FilesBrowserEvent.OpenFolder(FilesItemId(8L)),
                FilesBrowserEvent.OpenExternalItem(file(99L)),
            )
            navigation.forEach {
                val rejected = FilesBrowserReducer.reduce(state, it)
                assertFalse(rejected.consumed)
                assertEquals(state, rejected.state)
            }
        }
    }

    @Test
    fun movingToCachedAncestorReloadsItOnBackAndKeepsItsViewport() {
        val source = file(3L)
        val viewport = FilesViewportPosition(2, 18)
        val root = FilesBrowserReducer.reduce(
            loadedRoot(listOf(source)), FilesBrowserEvent.ViewportChanged(viewport),
        ).state
        val opening = FilesBrowserReducer.reduce(root, FilesBrowserEvent.OpenFolder(source.id))
        val childItem = item.copy(parentId = source.id)
        val child = reload(opening, FilesPage(listOf(childItem), null))
        val moving = FilesBrowserReducer.reduce(
            child, move.copy(folderId = source.id, destinationId = FilesFolder.Root.id),
        )
        val checking = FilesBrowserReducer.reduce(moving.state, FilesBrowserEvent.MoveFinished(
            checkNotNull(moving.effect).requestId, FilesRepositoryResult.Success(emptyList()),
        ))
        val reloading = check(checking, FilesRepositoryResult.Success(item.copy(parentId = FilesFolder.Root.id)))
        val finished = reload(reloading, FilesPage(emptyList(), null))
        val back = FilesBrowserReducer.reduce(finished, FilesBrowserEvent.NavigateBack)
        assertEquals(FilesFolder.Root.id, (back.effect as FilesBrowserEffect.LoadFolder).folderId)
        val refreshed = reload(back, FilesPage(listOf(source, item), null))
        assertEquals(listOf(source, item), refreshed.current.content.items())
        assertEquals(viewport, refreshed.current.content.viewport())
        assertFalse(refreshed.current.needsReload)
    }

    @Test
    fun sourcePresenceOnALaterPageCorrectsTheEarlierDestinationRead() {
        val reloading = check(finishPost(), FilesRepositoryResult.Success(item.copy(parentId = move.destinationId)))
        val first = reload(reloading, FilesPage(emptyList(), FilesCursor("next")))
        assertEquals(FilesMoveStatus.MOVED, first.current.moveOutcome?.status)
        val paging = FilesBrowserReducer.reduce(first, FilesBrowserEvent.LoadNextPage)
        val appended = reload(paging, FilesPage(listOf(item), null))
        assertEquals(FilesMoveStatus.STILL_PRESENT, appended.current.moveOutcome?.status)
        assertNull(FilesBrowserReducer.reduce(appended, FilesBrowserEvent.Retry).effect)
    }

    private fun finishPost(
        result: FilesRepositoryResult<List<FileMoveError>> = FilesRepositoryResult.Success(emptyList()),
    ): FilesBrowserTransition {
        val moving = FilesBrowserReducer.reduce(loadedRoot(), move)
        return FilesBrowserReducer.reduce(
            moving.state, FilesBrowserEvent.MoveFinished(checkNotNull(moving.effect).requestId, result),
        )
    }

    private fun check(checking: FilesBrowserTransition, result: FilesRepositoryResult<FilesItem>) =
        FilesBrowserReducer.reduce(
            checking.state, FilesBrowserEvent.MoveChecked(checkNotNull(checking.effect).requestId, result),
        )

    private fun reload(loading: FilesBrowserTransition, page: FilesPage) = FilesBrowserReducer.reduce(
        loading.state, FilesBrowserEvent.LoadSucceeded(checkNotNull(loading.effect).requestId, page),
    ).state

    private fun loadedRoot(items: List<FilesItem> = listOf(item)) =
        reload(FilesBrowserReducer.start(), FilesPage(items, null))
    private fun file(id: Long) = FilesItem(
        FilesItemId(id), FilesFolder.Root.id, "folder-$id", PutioFileType.FOLDER, 1L, "2026-09-06",
    )
    private fun failure(message: String) = FilesFailure.Unexpected(IllegalStateException(message))
    private fun notFound(): FilesFailure = FilesFailure.ApiRejected(404, "FileNotFound", PutioApiException(
        request = PutioRequestData("GET", "https://api.put.io/v2/files/7"), resolvedStatusCode = 404,
        resolvedErrorType = "FileNotFound",
        envelope = PutioApiErrorEnvelope(errorType = "FileNotFound", statusCode = 404),
        responseBody = "{}", message = "Not found",
    ))
}
