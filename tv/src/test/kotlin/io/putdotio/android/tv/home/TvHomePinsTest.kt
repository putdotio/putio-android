package io.putdotio.android.tv.home

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FakePinnedFiles
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvHomePinsTest {
    private val pins = FakePinnedFiles(listOf(Series, Clip, Gone))
    private val reads = mutableListOf<Long>()
    private val resolver = object : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> {
            reads += itemId.value
            return when (itemId) {
                Series -> PutioResult.Success(item(Series, "Series", PutioFileType.FOLDER))
                Clip -> PutioResult.Success(item(Clip, "clip.mp4", PutioFileType.VIDEO))
                else -> PutioResult.Failure(notFound())
            }
        }
    }
    private val counts = FilesFolderCounts { PutioResult.Success(12) }

    @Test
    fun pinsBecomeCardsInOrderAndAFileGoneIsUnpinned() = runTest(UnconfinedTestDispatcher()) {
        val home = TvHomePins(pins, resolver, counts, backgroundScope)

        val cards = (home.row.value as TvPinnedRow.Ready).cards
        assertEquals(listOf("Series", "clip.mp4"), cards.map { it.item.name })
        assertEquals(listOf(12, null), cards.map { it.itemCount })
        assertEquals(listOf(Series, Clip), pins.pins.value)

        pins.pin(FilesItemId(Clip.value))
        assertEquals(listOf("clip.mp4", "Series"), (home.row.value as TvPinnedRow.Ready).cards.map { it.item.name })
        assertEquals("Known cards are not read again", 3, reads.size)

        home.refresh()
        assertEquals(5, reads.size)
    }

    @Test
    fun aFailedConfigReadShowsAndRetriesAndA401IsKeptForTheSession() = runTest(UnconfinedTestDispatcher()) {
        val unread = FakePinnedFiles(initial = null)
        val failure = PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))
        unread.failure.value = failure
        val home = TvHomePins(unread, resolver, counts, backgroundScope)
        assertEquals(TvPinnedRow.Failed(failure), home.row.value)

        home.refresh()
        assertEquals(1, unread.retries)

        val rejected = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
        val locked = TvHomePins(
            FakePinnedFiles(listOf(Series)),
            object : FilesItemResolver {
                override suspend fun resolveItem(itemId: FilesItemId) = PutioResult.Failure(rejected)
            },
            counts,
            backgroundScope,
        )
        assertEquals(rejected, locked.rejected.value)
        assertTrue((locked.row.value as TvPinnedRow.Ready).cards.isEmpty())
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
        val Series = FilesItemId(1L)
        val Clip = FilesItemId(2L)
        val Gone = FilesItemId(3L)
    }
}
