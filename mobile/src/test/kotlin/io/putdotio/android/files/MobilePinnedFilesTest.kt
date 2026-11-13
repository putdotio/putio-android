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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobilePinnedFilesTest {
    private val reads = mutableListOf<FilesItemId>()
    private var offline = false
    private val resolve: suspend (FilesItemId) -> PutioResult<FilesItem> = { id ->
        reads += id
        when {
            offline -> PutioResult.Failure(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline")))
            else -> resolveOnline(id)
        }
    }
    private fun resolveOnline(id: FilesItemId): PutioResult<FilesItem> =
        when (id) {
            Series -> PutioResult.Success(item(Series, "Series", PutioFileType.FOLDER))
            Clip -> PutioResult.Success(item(Clip, "clip.mp4", PutioFileType.VIDEO))
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
