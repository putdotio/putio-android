package io.putdotio.android.files

import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesDeleteReducerTest {
    private val item = file(7L)
    private val event = FilesBrowserEvent.Delete(FilesFolder.Root.id, item.id, FilesDeleteMode.TRASH)
    private val accepted = FileDeleteResult(status = "OK")

    @Test
    fun rejectsSentinelsMissingItemsStaleFoldersAndDuplicateSubmission() {
        val root = loadedRoot(listOf(item, file(0L), file(-1L)))
        val invalid = listOf(
            event.copy(itemId = FilesItemId(0L)), event.copy(itemId = FilesItemId(-1L)),
            event.copy(itemId = FilesItemId(99L)), event.copy(folderId = FilesItemId(99L)),
        )
        invalid.forEach { assertFalse(FilesBrowserReducer.reduce(root, it).consumed) }
        for (mode in FilesDeleteMode.entries) {
            val deleting = FilesBrowserReducer.reduce(root, event.copy(mode = mode))
            val effect = deleting.effect as FilesBrowserEffect.Delete
            assertEquals(item.id, effect.itemId)
            assertEquals(mode, effect.mode)
            val conflicts = listOf(
                event, FilesBrowserEvent.Refresh, FilesBrowserEvent.SelectSort(FilesSort.SIZE_ASCENDING),
            )
            conflicts.forEach { assertFalse(FilesBrowserReducer.reduce(deleting.state, it).consumed) }
            assertFalse(FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.OpenFolder(item.id)).consumed)
            assertFalse(FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.OpenExternalItem(item)).consumed)
            assertFalse(FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.OpenExternalItem(
                file(8L).copy(parentId = item.id, type = PutioFileType.VIDEO),
            )).consumed)
            assertFalse(FilesBrowserReducer.reduce(
                deleting.state, FilesBrowserEvent.MutationSucceeded(effect.requestId),
            ).consumed)
        }
    }

    @Test
    fun acknowledgementChecksExactIdBeforeReloadAndDoesNotInferRemovalFromFirstPageAbsence() {
        val viewport = FilesViewportPosition(4, 20)
        val root = FilesBrowserReducer.reduce(loadedRoot(), FilesBrowserEvent.ViewportChanged(viewport)).state
        val paging = FilesBrowserReducer.reduce(root, FilesBrowserEvent.LoadNextPage)
        val deleting = FilesBrowserReducer.reduce(paging.state, event)
        val checking = acknowledge(deleting)
        val check = checking.effect as FilesBrowserEffect.CheckDelete
        assertEquals(item.id, check.itemId)
        assertEquals(listOf(item), checking.state.current.content.items())
        assertFalse(FilesBrowserReducer.reduce(checking.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(paging.effect).requestId, FilesPage(listOf(file(8L)), null),
        )).consumed)
        assertFalse(FilesBrowserReducer.reduce(checking.state, FilesBrowserEvent.DeleteFinished(
            checkNotNull(deleting.effect).requestId, FilesRepositoryResult.Success(accepted),
        )).consumed)
        val reloading = checked(checking, FilesRepositoryResult.Success(item))
        val finished = finishReload(reloading, listOf(file(8L)))
        assertEquals(FilesDeleteStatus.STILL_PRESENT, finished.current.deleteOutcome?.status)
        assertEquals(listOf(file(8L)), finished.current.content.items())
        assertEquals(viewport, finished.current.content.viewport())
        assertEquals(root.current.viewportGeneration, finished.current.viewportGeneration)
        assertEquals(FilesFolderOperation.Idle, finished.current.operation)
    }

    @Test
    fun onlyStructuredNotFoundEstablishesUnavailableAndReloadFailureRetriesOnlyFolderRead() {
        val checking = acknowledge(FilesBrowserReducer.reduce(loadedRoot(), event))
        val reloading = checked(checking, FilesRepositoryResult.Failure(apiFailure(404)))
        assertEquals(FilesDeleteStatus.NO_LONGER_AVAILABLE, reloading.state.current.deleteOutcome?.status)
        val failed = FilesBrowserReducer.reduce(reloading.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(reloading.effect).requestId, unexpected("offline reload"),
        )).state
        assertFalse(FilesBrowserReducer.reduce(failed, event).consumed)
        assertFalse(FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Refresh).consumed)
        val retry = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)
        assertTrue(retry.effect is FilesBrowserEffect.LoadFolder)
        val finished = finishReload(retry, emptyList())
        assertEquals(FilesDeleteStatus.NO_LONGER_AVAILABLE, finished.current.deleteOutcome?.status)
        assertTrue(finished.current.content is FilesContent.Empty)
    }

    @Test
    fun laterFolderPresenceCorrectsAnEarlierNotFoundResultWithoutRetryingDelete() {
        val checking = acknowledge(FilesBrowserReducer.reduce(loadedRoot(), event))
        val reloading = checked(checking, FilesRepositoryResult.Failure(apiFailure(404)))
        assertEquals(FilesDeleteStatus.NO_LONGER_AVAILABLE, reloading.state.current.deleteOutcome?.status)
        val finished = finishReload(reloading)
        val outcome = checkNotNull(finished.current.deleteOutcome)
        assertEquals(FilesDeleteStatus.STILL_PRESENT, outcome.status)
        assertSame(accepted, outcome.response)
        assertEquals(listOf(item), finished.current.content.items())
        assertEquals(FilesFolderOperation.Idle, finished.current.operation)
        assertNull(FilesBrowserReducer.reduce(finished, FilesBrowserEvent.Retry).effect)
    }

    @Test
    fun laterPagePresenceCorrectsAnEarlierNotFoundResultWithoutRetryingDelete() {
        val checking = acknowledge(FilesBrowserReducer.reduce(loadedRoot(), event))
        val reloading = checked(checking, FilesRepositoryResult.Failure(apiFailure(404)))
        val firstPage = FilesBrowserReducer.reduce(reloading.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(reloading.effect).requestId, FilesPage(listOf(file(8L)), FilesCursor("after-delete")),
        )).state
        val previousOutcome = checkNotNull(firstPage.current.deleteOutcome)
        assertEquals(FilesDeleteStatus.NO_LONGER_AVAILABLE, previousOutcome.status)
        val paging = FilesBrowserReducer.reduce(firstPage, FilesBrowserEvent.LoadNextPage)
        assertTrue(paging.effect is FilesBrowserEffect.LoadNextPage)
        val appended = FilesBrowserReducer.reduce(paging.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(paging.effect).requestId, FilesPage(listOf(item), null),
        ))
        assertEquals(
            previousOutcome.copy(status = FilesDeleteStatus.STILL_PRESENT),
            appended.state.current.deleteOutcome,
        )
        assertEquals(listOf(file(8L), item), appended.state.current.content.items())
        assertEquals(FilesFolderOperation.Idle, appended.state.current.operation)
        assertNull(appended.effect)
        assertNull(FilesBrowserReducer.reduce(appended.state, FilesBrowserEvent.Retry).effect)
    }

    @Test
    fun ambiguousMutationAndReadFailuresRetainCauseAndRetryOnlyExactItemRead() {
        val deleting = FilesBrowserReducer.reduce(loadedRoot(), event)
        val originalFailure = unexpected("connection lost after submission")
        // Controller-level unexpected throws also arrive through LoadFailed.
        val checking = FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(deleting.effect).requestId, originalFailure,
        ))
        assertTrue(checking.effect is FilesBrowserEffect.CheckDelete)
        assertSame(originalFailure, checking.state.current.deleteOutcome?.failure)
        for (failure in listOf(apiFailure(401), apiFailure(403), unexpected("offline read"))) {
            val failed = checked(checking, FilesRepositoryResult.Failure(failure)).state
            assertEquals(FilesDeleteStatus.UNKNOWN, failed.current.deleteOutcome?.status)
            assertSame(failure, (failed.current.operation as FilesFolderOperation.Failed).failure)
            assertSame(originalFailure, failed.current.deleteOutcome?.failure)
            assertFalse(FilesBrowserReducer.reduce(failed, event).consumed)
            assertFalse(FilesBrowserReducer.reduce(failed, FilesBrowserEvent.OpenFolder(item.id)).consumed)
            val retry = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)
            assertTrue(retry.effect is FilesBrowserEffect.CheckDelete)
            val reloading = checked(retry, FilesRepositoryResult.Success(item))
            assertEquals(FilesDeleteStatus.STILL_PRESENT, finishReload(reloading).current.deleteOutcome?.status)
        }
    }

    @Test
    fun skippedOutcomeRetainsCursorAndCountThroughAuthoritativeReloadWithoutFallback() {
        val response = FileDeleteResult(cursor = "skipped-folder", skipped = 1, status = "OK")
        val checking = acknowledge(FilesBrowserReducer.reduce(loadedRoot(), event), response)
        val reloading = checked(checking, FilesRepositoryResult.Success(item))
        val finished = finishReload(reloading)
        val outcome = checkNotNull(finished.current.deleteOutcome)
        assertEquals(FilesDeleteStatus.SKIPPED, outcome.status)
        assertSame(response, outcome.response)
        assertEquals(FilesDeleteMode.TRASH, outcome.intent.mode)
        assertEquals(item.name, outcome.itemName)
        assertNull(FilesBrowserReducer.reduce(finished, FilesBrowserEvent.Retry).effect)
    }

    @Test
    fun folderLimitRefusalPreservesStructuredCauseAndNeverFallsBackToPermanentDelete() {
        val deleting = FilesBrowserReducer.reduce(loadedRoot(), event)
        val failure = apiFailure(400, "FileDeleteChildrenLimitError")
        val checking = FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.DeleteFinished(
            checkNotNull(deleting.effect).requestId, FilesRepositoryResult.Failure(failure),
        ))
        val finished = finishReload(checked(checking, FilesRepositoryResult.Success(item)))
        assertSame(failure, finished.current.deleteOutcome?.failure)
        assertEquals(FilesDeleteStatus.STILL_PRESENT, finished.current.deleteOutcome?.status)
        assertEquals(listOf(item), finished.current.content.items())
        assertNull(FilesBrowserReducer.reduce(finished, FilesBrowserEvent.Retry).effect)
        val confirmedAgain = FilesBrowserReducer.reduce(finished, event)
        assertEquals(FilesDeleteMode.TRASH, (confirmedAgain.effect as FilesBrowserEffect.Delete).mode)
    }

    @Test
    fun wrongResolvedIdAndUnexpectedReadThrowCannotCompleteTheDeletion() {
        val checking = acknowledge(FilesBrowserReducer.reduce(loadedRoot(), event))
        val failed = checked(checking, FilesRepositoryResult.Success(file(8L))).state
        assertEquals(FilesDeleteStatus.UNKNOWN, failed.current.deleteOutcome?.status)
        assertTrue((failed.current.operation as FilesFolderOperation.Failed).failure is FilesFailure.Unexpected)
        val thrown = FilesBrowserReducer.reduce(checking.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(checking.effect).requestId, unexpected("reader threw"),
        )).state
        assertEquals(FilesDeleteStatus.UNKNOWN, thrown.current.deleteOutcome?.status)
        assertTrue(FilesBrowserReducer.reduce(thrown, FilesBrowserEvent.Retry).effect is FilesBrowserEffect.CheckDelete)
    }

    @Test
    fun serverFailureClaimingNotFoundCannotEstablishItemAbsence() {
        val checking = acknowledge(FilesBrowserReducer.reduce(loadedRoot(), event))
        val failure = apiFailure(404, httpStatusCode = 500)
        val failed = checked(checking, FilesRepositoryResult.Failure(failure)).state
        assertEquals(FilesDeleteStatus.UNKNOWN, failed.current.deleteOutcome?.status)
        assertSame(failure, (failed.current.operation as FilesFolderOperation.Failed).failure)
        assertEquals(listOf(item), failed.current.content.items())
        assertTrue(FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry).effect is FilesBrowserEffect.CheckDelete)
    }

    @Test
    fun authenticationRejectionIsVisibleWithoutAnotherRequest() {
        val deleting = FilesBrowserReducer.reduce(loadedRoot(), event)
        val failure = apiFailure(401)
        val rejected = FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.DeleteFinished(
            checkNotNull(deleting.effect).requestId, FilesRepositoryResult.Failure(failure),
        ))
        assertNull(rejected.effect)
        assertSame(failure, (rejected.state.current.operation as FilesFolderOperation.Failed).failure)
    }

    @Test
    fun navigationCannotDiscardDeleteOrItsReadOnlyRecovery() {
        val opening = FilesBrowserReducer.reduce(loadedRoot(), FilesBrowserEvent.OpenFolder(item.id))
        val child = FilesBrowserReducer.reduce(opening.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(opening.effect).requestId, FilesPage(listOf(file(8L)), null),
        )).state
        val childDelete = FilesBrowserReducer.reduce(child, event.copy(folderId = item.id, itemId = FilesItemId(8L)))
        val checking = acknowledge(childDelete)
        val checkFailed = checked(checking, FilesRepositoryResult.Failure(unexpected("offline")))
        val reloading = checked(checking, FilesRepositoryResult.Success(file(8L)))
        val reloadFailed = FilesBrowserReducer.reduce(reloading.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(reloading.effect).requestId, unexpected("reload offline"),
        ))
        val navigation = listOf(
            FilesBrowserEvent.NavigateBack,
            FilesBrowserEvent.OpenExternalItem(file(99L)),
            FilesBrowserEvent.OpenExternalItem(file(99L).copy(type = PutioFileType.VIDEO)),
        )
        for (pending in listOf(childDelete, checking, checkFailed, reloading, reloadFailed)) {
            navigation.forEach { navigationEvent ->
                val blocked = FilesBrowserReducer.reduce(pending.state, navigationEvent)
                assertFalse(blocked.consumed)
                assertSame(pending.state, blocked.state)
                assertNull(blocked.effect)
            }
        }
        val retry = FilesBrowserReducer.reduce(checkFailed.state, FilesBrowserEvent.Retry)
        assertTrue(retry.effect is FilesBrowserEffect.CheckDelete)
        val finished = finishReload(checked(retry, FilesRepositoryResult.Success(file(8L))))
        navigation.forEach { assertTrue(FilesBrowserReducer.reduce(finished, it).consumed) }
    }

    private fun acknowledge(
        deleting: FilesBrowserTransition,
        response: FileDeleteResult = accepted,
    ): FilesBrowserTransition =
        FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.DeleteFinished(
            checkNotNull(deleting.effect).requestId, FilesRepositoryResult.Success(response),
        ))

    private fun checked(
        checking: FilesBrowserTransition,
        result: FilesRepositoryResult<FilesItem>,
    ): FilesBrowserTransition = FilesBrowserReducer.reduce(
        checking.state, FilesBrowserEvent.DeleteChecked(checkNotNull(checking.effect).requestId, result),
    )

    private fun finishReload(
        reloading: FilesBrowserTransition,
        items: List<FilesItem> = listOf(item),
    ): FilesBrowserState =
        FilesBrowserReducer.reduce(reloading.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(reloading.effect).requestId, FilesPage(items, null),
        )).state

    private fun loadedRoot(items: List<FilesItem> = listOf(item)): FilesBrowserState {
        val started = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(started.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(started.effect).requestId, FilesPage(items, FilesCursor("next")),
        )).state
    }

    private fun file(id: Long) =
        FilesItem(FilesItemId(id), FilesItemId(0L), "folder-$id", PutioFileType.FOLDER, 1L, "2026-09-06")
    private fun unexpected(message: String) = FilesFailure.Unexpected(IllegalStateException(message))
    private fun apiFailure(
        code: Int,
        type: String? = null,
        httpStatusCode: Int = code,
    ): FilesFailure = PutioApiException(
        request = PutioRequestData("GET", "https://api.put.io/v2/files/7"),
        resolvedStatusCode = code, httpStatusCode = httpStatusCode, resolvedErrorType = type,
        envelope = PutioApiErrorEnvelope(statusCode = code, errorType = type),
        responseBody = "{}", message = "Rejected",
    ).toFilesFailure()
}
