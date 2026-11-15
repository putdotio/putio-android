package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobilePinnedFilesTest {
    private val reads = mutableListOf<FilesItemId>()
    private var offline = false
    private var renamed = false
    private var rejected = false
    private val resolve = object : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> {
            reads += itemId
            return when {
                rejected -> PutioResult.Failure(PutioFailure.AuthenticationRequired(PutioConfigurationException("401")))
                offline -> PutioResult.Failure(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline")))
                else -> resolveOnline(itemId)
            }
        }
    }
    private fun resolveOnline(id: FilesItemId): PutioResult<FilesItem> =
        when (id) {
            Series -> PutioResult.Success(item(Series, "Series", PutioFileType.FOLDER))
            Clip -> PutioResult.Success(item(Clip, if (renamed) "renamed.mp4" else "clip.mp4", PutioFileType.VIDEO))
            Offline -> PutioResult.Failure(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline")))
            else -> PutioResult.Failure(notFound())
        }

    @Test
    fun pinsLoadByIdInPinOrderAndAFileGoneIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val pins = FakePinnedFiles(listOf(Series, Gone, Clip))
        val pinned = MobilePinnedFiles(pins, resolve, backgroundScope)

        assertEquals(listOf("Series", "clip.mp4"), pinned.items.value.map { it.name })
        assertEquals(listOf(Series, Clip), pins.pins.value)
    }

    @Test
    fun pinsThatFailToLoadLeaveTheSectionAbsent() = runTest(UnconfinedTestDispatcher()) {
        val pins = FakePinnedFiles(initial = null)
        pins.failure.value = PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))
        val pinned = MobilePinnedFiles(pins, resolve, backgroundScope)

        assertEquals(emptyList<FilesItem>(), pinned.items.value)
    }

    @Test
    fun aPinThatCannotBeReadIsLeftOutButStaysPinned() = runTest(UnconfinedTestDispatcher()) {
        val pins = FakePinnedFiles(listOf(Offline, Clip))
        val pinned = MobilePinnedFiles(pins, resolve, backgroundScope)

        assertEquals(listOf("clip.mp4"), pinned.items.value.map { it.name })
        assertEquals(listOf(Offline, Clip), pins.pins.value)
    }

    @Test
    fun aRefreshThatFailsKeepsThePinsAlreadyShown() = runTest(UnconfinedTestDispatcher()) {
        val pins = FakePinnedFiles(listOf(Series, Clip))
        val pinned = MobilePinnedFiles(pins, resolve, backgroundScope)
        offline = true
        reads.clear()

        pinned.refresh()

        assertEquals(listOf(Series, Clip), reads)
        assertEquals(listOf("Series", "clip.mp4"), pinned.items.value.map { it.name })
    }

    @Test
    fun pinningShowsTheItemFirstWithoutReadingItAgain() = runTest(UnconfinedTestDispatcher()) {
        val pins = FakePinnedFiles(listOf(Series))
        val pinned = MobilePinnedFiles(pins, resolve, backgroundScope)
        reads.clear()

        pinned.setPinned(item(Fresh, "notes.pdf", PutioFileType.PDF), pinned = true)

        assertEquals(listOf("notes.pdf", "Series"), pinned.items.value.map { it.name })
        assertEquals(emptyList<FilesItemId>(), reads)

        pinned.setPinned(item(Series, "Series", PutioFileType.FOLDER), pinned = false)
        assertEquals(listOf("notes.pdf"), pinned.items.value.map { it.name })
    }

    @Test
    fun aRefusedPinEditBecomesOneNoticeNamingTheItem() = runTest(UnconfinedTestDispatcher()) {
        val pins = FakePinnedFiles(emptyList())
        val pinned = MobilePinnedFiles(pins, resolve, backgroundScope)
        val notices = mutableListOf<MobileFilesNotice>()
        backgroundScope.launch { pinned.notices.collect { notices += it } }

        pinned.setPinned(item(Clip, "clip.mp4", PutioFileType.VIDEO), pinned = true)
        pins.editFailure.value = PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))

        assertEquals(listOf("clip.mp4"), notices.map { it.name })
        assertEquals(R.string.mobile_files_pin_failed, notices.single().message)
        assertNull(pins.editFailure.value)
    }

    @Test
    fun aRenamedPinShowsItsNewNameWithoutAPullToRefresh() = runTest {
        val pinned = MobilePinnedFiles(FakePinnedFiles(listOf(Series, Clip)), resolve, backgroundScope)
        val browser = MutableStateFlow(root())
        backgroundScope.launch { pinned.follow(browser) }
        advanceUntilIdle()

        renamed = true
        browser.value = root(
            renameCompletion = FilesRenameCompletion(
                FilesRequestId(7L),
                FilesFolderOperationIntent.Rename(Clip, "renamed.mp4"),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("Series", "renamed.mp4"), pinned.items.value.map { it.name })
    }

    @Test
    fun aMovedPinIsReadAgain() = runTest {
        val pinned = MobilePinnedFiles(FakePinnedFiles(listOf(Series, Clip)), resolve, backgroundScope)
        val browser = MutableStateFlow(root())
        backgroundScope.launch { pinned.follow(browser) }
        advanceUntilIdle()
        reads.clear()

        val move = FilesFolderOperationIntent.Move(Clip, Series)
        browser.value = root(moveOutcome = FilesMoveOutcome(FilesRequestId(8L), move, "clip.mp4"))
        advanceUntilIdle()
        assertEquals("A move still being checked reads nothing", emptyList<FilesItemId>(), reads)

        browser.value = root(
            moveOutcome = FilesMoveOutcome(FilesRequestId(8L), move, "clip.mp4", status = FilesMoveStatus.MOVED),
        )
        advanceUntilIdle()
        assertEquals(listOf(Series, Clip), reads)
    }

    @Test
    fun aTrashedPinLeavesPinnedAndUndoBringsItBack() = runTest {
        val pins = FakePinnedFiles(listOf(Series, Clip))
        val pinned = MobilePinnedFiles(pins, resolve, backgroundScope)
        val browser = MutableStateFlow(root())
        backgroundScope.launch { pinned.follow(browser) }
        advanceUntilIdle()

        val trash = FilesDeleteOutcome(
            requestId = FilesRequestId(9L),
            intent = FilesFolderOperationIntent.Delete(Clip, FilesDeleteMode.TRASH),
            itemName = "clip.mp4",
            status = FilesDeleteStatus.NO_LONGER_AVAILABLE,
        )
        browser.value = root(deleteOutcome = trash)
        advanceUntilIdle()
        assertEquals(listOf("Series"), pinned.items.value.map { it.name })
        assertEquals(listOf(Series, Clip), pins.pins.value)

        pinned.trashUndone(trash)
        advanceUntilIdle()
        assertEquals(listOf("Series", "clip.mp4"), pinned.items.value.map { it.name })
    }

    @Test
    fun aSessionRevokedWhilePinsLoadIsReportedForSignOut() = runTest {
        rejected = true
        val pinned = MobilePinnedFiles(FakePinnedFiles(listOf(Clip)), resolve, backgroundScope)
        assertNull(pinned.sessionRejected.value)

        advanceUntilIdle()

        assertTrue(pinned.sessionRejected.value is PutioFailure.AuthenticationRequired)
    }

    @Test
    fun aRevokedSessionReadingThePinListIsReportedForSignOut() = runTest {
        val pins = FakePinnedFiles(initial = null)
        val pinned = MobilePinnedFiles(pins, resolve, backgroundScope)
        pins.failure.value = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))

        advanceUntilIdle()

        assertTrue(pinned.sessionRejected.value is PutioFailure.AuthenticationRequired)
    }

    private fun root(
        renameCompletion: FilesRenameCompletion? = null,
        moveOutcome: FilesMoveOutcome? = null,
        deleteOutcome: FilesDeleteOutcome? = null,
    ) = filesBrowserState(
        stack = listOf(
            FilesFolderState(
                FilesFolder.Root,
                FilesContent.Ready(emptyList(), FilesPaging.Complete),
                renameCompletion = renameCompletion,
                moveOutcome = moveOutcome,
                deleteOutcome = deleteOutcome,
            ),
        ),
        nextRequestValue = 10L,
    )

    private fun item(id: FilesItemId, name: String, type: PutioFileType) = FilesItem(
        id = id,
        parentId = FilesItemId(0L),
        name = name,
        type = type,
        sizeBytes = 276_000_000L,
        createdAt = "2026-10-23T10:00:00Z",
    )

    private fun notFound(): PutioFailure = PutioFailure.ApiRejected(
        statusCode = 404,
        errorType = "NotFound",
        cause = PutioApiException(
            request = PutioRequestData("GET", "https://api.put.io/v2/files/3"),
            resolvedStatusCode = 404,
            httpStatusCode = 404,
            resolvedErrorType = null,
            envelope = PutioApiErrorEnvelope(statusCode = 404, errorType = null),
            responseBody = "{}",
            message = "Not found",
        ),
    )

    private companion object {
        val Series = FilesItemId(11L)
        val Clip = FilesItemId(12L)
        val Gone = FilesItemId(13L)
        val Offline = FilesItemId(14L)
        val Fresh = FilesItemId(15L)
    }
}
