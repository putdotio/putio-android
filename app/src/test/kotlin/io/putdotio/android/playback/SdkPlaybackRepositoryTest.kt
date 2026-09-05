package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FilesContinueQuery
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesListResponse
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PlaybackRequest
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

class SdkPlaybackRepositoryTest {
    @Test
    fun resolvesHlsWithAccountCredentialAndResumePreference() =
        runBlocking {
            var request: PlaybackRequest? = null
            val repository = SdkPlaybackRepository(
                playbackPreference = { PlaybackPreference.HLS },
                loadAccount = { account(downloadToken = Token, useStartFrom = true) },
                resolvePlayback = {
                    request = it
                    io.putdotio.sdk.files.PlaybackResolution.Conversion(PlaybackConversionState.Queued)
                },
            )

            val result = repository.resolve(Target) as PlaybackRepositoryResult.Success

            assertTrue(result.value is PlaybackResolution.Conversion)
            assertEquals(Target.fileId.value, request?.fileId)
            assertEquals(PlaybackPreference.HLS, request?.preference)
            assertTrue(request?.useStartFrom == true)
            assertEquals("<redacted media credential>", request?.mediaCredential.toString())
        }

    @Test
    fun preservesTheResolvedReadySource() =
        runBlocking {
            val source = playbackSource()
            val repository = SdkPlaybackRepository(
                playbackPreference = { PlaybackPreference.MP4 },
                loadAccount = { account(downloadToken = Token, useStartFrom = true) },
                resolvePlayback = { io.putdotio.sdk.files.PlaybackResolution.Ready(source) },
            )

            val result = repository.resolve(Target) as PlaybackRepositoryResult.Success

            assertSame(source, (result.value as PlaybackResolution.Ready).source)
        }

    @Test
    fun preservesDisabledAccountResumePreference() =
        runBlocking {
            var request: PlaybackRequest? = null
            val repository = SdkPlaybackRepository(
                playbackPreference = { PlaybackPreference.MP4 },
                loadAccount = { account(downloadToken = Token, useStartFrom = false) },
                resolvePlayback = {
                    request = it
                    io.putdotio.sdk.files.PlaybackResolution.Unsupported(PutioFileType.TEXT)
                },
            )

            repository.resolve(Target)

            assertFalse(requireNotNull(request).useStartFrom)
        }

    @Test
    fun missingDownloadTokenIsAnExplicitConfigurationFailure() =
        runBlocking {
            var resolverCalled = false
            val repository = SdkPlaybackRepository(
                playbackPreference = { PlaybackPreference.MP4 },
                loadAccount = { account(downloadToken = null, useStartFrom = true) },
                resolvePlayback = {
                    resolverCalled = true
                    error("Resolver must not run")
                },
            )

            val result = repository.resolve(Target) as PlaybackRepositoryResult.Failure

            assertTrue(result.failure is PlaybackFailure.MediaCredentialUnavailable)
            assertTrue(result.failure.cause is MissingPlaybackCredentialException)
            assertFalse(resolverCalled)
        }

    @Test
    fun authoritativeAccountAndPlaybackRejectionsExpireTheSession() =
        runBlocking {
            val accountFailure = apiFailure(401)
            val playbackFailure = apiFailure(401)
            val accountResult = SdkPlaybackRepository(
                playbackPreference = { PlaybackPreference.MP4 },
                loadAccount = { throw accountFailure },
                resolvePlayback = { error("Resolver must not run") },
            ).resolve(Target) as PlaybackRepositoryResult.Failure
            val playbackResult = SdkPlaybackRepository(
                playbackPreference = { PlaybackPreference.MP4 },
                loadAccount = { account(Token, useStartFrom = true) },
                resolvePlayback = { throw playbackFailure },
            ).resolve(Target) as PlaybackRepositoryResult.Failure

            assertSame(accountFailure, (accountResult.failure as PlaybackFailure.AuthenticationRequired).cause)
            assertSame(playbackFailure, (playbackResult.failure as PlaybackFailure.AuthenticationRequired).cause)
        }

    @Test
    fun authoritativeHttpStatusWinsOverAContradictoryApiEnvelope() =
        runBlocking {
            val failure = apiFailure(
                httpStatusCode = 401,
                envelopeStatusCode = 403,
                reason = PutioOperationErrorReason.StatusCode(403),
            )
            val result = SdkPlaybackRepository(
                playbackPreference = { PlaybackPreference.MP4 },
                loadAccount = { throw failure },
                resolvePlayback = { error("Resolver must not run") },
            ).resolve(Target) as PlaybackRepositoryResult.Failure

            assertTrue(result.failure is PlaybackFailure.AuthenticationRequired)
            assertSame(failure, result.failure.cause)
        }

    @Test
    fun neverConvertsCancellationIntoAUiFailure() {
        val cancellation = CancellationException("route closed")
        try {
            runBlocking {
                SdkPlaybackRepository(
                    playbackPreference = { PlaybackPreference.MP4 },
                    loadAccount = { throw cancellation },
                    resolvePlayback = { error("Resolver must not run") },
                ).resolve(Target)
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun readsTheCurrentPlaybackPreferenceForEachResolution() =
        runBlocking {
            var preference = PlaybackPreference.HLS
            var providerCalls = 0
            val requests = mutableListOf<PlaybackRequest>()
            val repository = SdkPlaybackRepository(
                playbackPreference = {
                    providerCalls += 1
                    preference
                },
                loadAccount = { account(downloadToken = Token, useStartFrom = true) },
                resolvePlayback = {
                    requests += it
                    io.putdotio.sdk.files.PlaybackResolution.Conversion(PlaybackConversionState.Queued)
                },
            )

            repository.resolve(Target)
            preference = PlaybackPreference.MP4
            repository.resolve(Target)

            assertEquals(listOf(PlaybackPreference.HLS, PlaybackPreference.MP4), requests.map { it.preference })
            assertEquals(2, providerCalls)
        }

    @Test
    fun findsNextVideoInExplicitFolderOrderAcrossPages() =
        runBlocking {
            val pages = mutableListOf<String>()
            val repository = nextRepository(
                listFolder = { parentId, query ->
                    assertEquals(7L, parentId)
                    assertEquals(PutioFileType.VIDEO, query.fileType)
                    assertEquals("NAME_ASC", query.sortBy)
                    assertEquals(200, query.perPage)
                    filePage(video(40L), cursor = "page-two")
                },
                continueListing = { cursor, query ->
                    pages += cursor
                    assertEquals(200, query.perPage)
                    when (cursor) {
                        "page-two" -> filePage(video(42L), cursor = "page-three")
                        "page-three" -> filePage(
                            video(99L, parentId = 8L),
                            video(100L).copy(fileType = PutioFileType.FOLDER),
                            video(43L),
                            cursor = "unused-page",
                        )
                        else -> error("Must stop after finding the next video")
                    }
                },
            )

            assertEquals(
                PlaybackNextResult.Found(PlaybackTarget(FilesItemId(43L), "video-43.mp4")),
                repository.findNextVideo(Target),
            )
            assertEquals(listOf("page-two", "page-three"), pages)
        }

    @Test
    fun lastSingletonAndMissingVideosEndWithoutWrapping() =
        runBlocking {
            for (files in listOf(listOf(video(40L), video(42L)), listOf(video(42L)), listOf(video(40L)))) {
                val repository = nextRepository(
                    listFolder = { _, _ -> FilesListResponse(files = files, status = "OK") },
                )
                assertEquals(PlaybackNextResult.Ended, repository.findNextVideo(Target))
            }
        }

    @Test
    fun rootFolderAndDuplicateNamesUseFileIdentity() =
        runBlocking {
            val repository = nextRepository(
                current = video(42L, parentId = 0L),
                listFolder = { parentId, _ ->
                    assertEquals(0L, parentId)
                    filePage(
                        video(42L, parentId = 0L).copy(name = "same.mp4"),
                        video(43L, parentId = 0L).copy(name = "same.mp4"),
                    )
                },
            )
            assertEquals(
                PlaybackNextResult.Found(PlaybackTarget(FilesItemId(43L), "same.mp4")),
                repository.findNextVideo(Target),
            )
        }

    @Test
    fun paginationFailurePreservesCauseAndRetryStartsFresh() =
        runBlocking {
            val failure = apiFailure(503)
            var attempts = 0
            val repository = nextRepository(
                listFolder = { _, _ -> filePage(video(42L), cursor = "next") },
                continueListing = { _, _ ->
                    if (attempts++ == 0) throw failure
                    filePage(video(43L))
                },
            )
            val failed = repository.findNextVideo(Target) as PlaybackNextResult.Failure
            assertSame(failure, failed.failure.cause)
            assertTrue(repository.findNextVideo(Target) is PlaybackNextResult.Found)
        }

    @Test
    fun repeatedCursorFailsInsteadOfLooping() =
        runBlocking {
            var continuations = 0
            val repository = nextRepository(
                listFolder = { _, _ -> filePage(video(40L), cursor = "repeated") },
                continueListing = { _, _ ->
                    continuations++
                    filePage(video(41L), cursor = "repeated")
                },
            )
            val result = repository.findNextVideo(Target) as PlaybackNextResult.Failure
            assertTrue(result.failure is PlaybackFailure.Unexpected)
            assertEquals(1, continuations)
        }

    @Test
    fun repeatedPageAfterCurrentCannotSelectAnEarlierVideo() =
        runBlocking {
            val repeated = filePage(video(40L), video(42L), cursor = "repeated")
            val repository = nextRepository(
                listFolder = { _, _ -> repeated },
                continueListing = { _, _ -> repeated },
            )
            val result = repository.findNextVideo(Target)
            assertTrue(result is PlaybackNextResult.Failure)
        }

    @Test
    fun overlappingTerminalPageSkipsEarlierVideosAndFindsTheSuccessor() =
        runBlocking {
            val repository = nextRepository(
                listFolder = { _, _ -> filePage(video(40L), video(42L), cursor = "next") },
                continueListing = { _, _ -> filePage(video(40L), video(42L), video(43L)) },
            )
            assertEquals(
                PlaybackNextResult.Found(PlaybackTarget(FilesItemId(43L), "video-43.mp4")),
                repository.findNextVideo(Target),
            )
        }

    @Test
    fun missingContinuationIsRecoverableRatherThanEndOfFolder() =
        runBlocking {
            val failure = apiFailure(404)
            val repository = nextRepository(
                listFolder = { _, _ -> filePage(video(42L), cursor = "expired") },
                continueListing = { _, _ -> throw failure },
            )
            val result = repository.findNextVideo(Target) as PlaybackNextResult.Failure
            assertSame(failure, result.failure.cause)
        }

    @Test
    fun paginationCancellationPropagates() {
        val cancellation = CancellationException("route closed")
        val repository = nextRepository(
            listFolder = { _, _ -> filePage(video(42L), cursor = "next") },
            continueListing = { _, _ -> throw cancellation },
        )
        try {
            runBlocking { repository.findNextVideo(Target) }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun actualNotFoundEndsButAuthenticationStillFails() =
        runBlocking {
            val notFound = apiFailure(404)
            val contradictory = apiFailure(
                httpStatusCode = 401,
                envelopeStatusCode = 404,
                reason = PutioOperationErrorReason.StatusCode(404),
            )
            val ended = nextFailureRepository(notFound).findNextVideo(Target)
            val rejected = nextFailureRepository(contradictory).findNextVideo(Target) as PlaybackNextResult.Failure

            assertEquals(PlaybackNextResult.Ended, ended)
            assertTrue(rejected.failure is PlaybackFailure.AuthenticationRequired)
            assertSame(contradictory, rejected.failure.cause)
        }

    @Test
    fun nextVideoLookupPreservesCancellation() {
        val cancellation = CancellationException("route closed")
        try {
            runBlocking { nextFailureRepository(cancellation).findNextVideo(Target) }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun nextFailureRepository(error: Throwable): SdkPlaybackRepository =
        SdkPlaybackRepository(
            playbackPreference = { PlaybackPreference.HLS },
            loadAccount = { error("Account must not load") },
            resolvePlayback = { error("Playback must not resolve") },
            loadFile = { throw error },
        )

    private fun nextRepository(
        current: PutioFile = video(42L),
        listFolder: suspend (Long, FilesListQuery) -> FilesListResponse,
        continueListing: suspend (String, FilesContinueQuery) -> FilesListResponse = { _, _ ->
            error("Unexpected pagination")
        },
    ): SdkPlaybackRepository =
        SdkPlaybackRepository(
            playbackPreference = { PlaybackPreference.HLS },
            loadAccount = { error("Account must not load") },
            resolvePlayback = { error("Playback must not resolve") },
            loadFile = { id ->
                assertEquals(Target.fileId.value, id)
                current
            },
            listFolder = listFolder,
            continueListing = continueListing,
        )

    private fun video(id: Long, parentId: Long = 7L): PutioFile =
        PutioFile(
            id = id,
            name = "video-$id.mp4",
            parentId = parentId,
            createdAt = "2026-09-05",
            fileType = PutioFileType.VIDEO,
        )

    private fun filePage(vararg files: PutioFile, cursor: String? = null): FilesListResponse =
        FilesListResponse(files = files.toList(), cursor = cursor, status = "OK")

    private fun account(
        downloadToken: AccountDownloadToken?,
        useStartFrom: Boolean,
    ): AccountInfo =
        AccountInfo(
            userId = 1L,
            username = "test",
            mail = "test@example.com",
            avatarUrl = "https://example.com/avatar.png",
            disk = AccountDisk(available = 1L, size = 2L, used = 1L),
            settings = AccountSettings(sortBy = "NAME_ASC", useStartFrom = useStartFrom),
            accountStatus = "active",
            downloadToken = downloadToken,
        )

    private fun apiFailure(
        httpStatusCode: Int,
        envelopeStatusCode: Int = httpStatusCode,
        reason: PutioOperationErrorReason? = null,
    ): PutioOperationException =
        PutioOperationException(
            domain = "account",
            operation = "getInfo",
            contract = null,
            reason = reason,
            underlyingError =
                PutioApiException(
                    request = PutioRequestData("GET", "https://api.put.io/v2/account/info"),
                    resolvedStatusCode = envelopeStatusCode,
                    httpStatusCode = httpStatusCode,
                    resolvedErrorType = null,
                    envelope = PutioApiErrorEnvelope(statusCode = envelopeStatusCode),
                    responseBody = "{}",
                    message = "Request rejected",
                ),
        )

    private fun playbackSource(): PlaybackSource =
        PlaybackSource(
            fileId = Target.fileId.value,
            kind = PlaybackSourceKind.HLS,
            url = credentialUrl("https://api.put.io/v2/files/42/hls/media.m3u8?token=secret"),
            startFromSeconds = 12.0,
            subtitles = PlaybackSubtitles.Embedded,
        )

    // Credential URLs can only be minted by the SDK resolver in production.
    private fun credentialUrl(value: String): PutioCredentialUrl =
        PutioCredentialUrl::class.java
            .getDeclaredConstructor(String::class.java)
            .newInstance(value)

    private companion object {
        val Target = PlaybackTarget(FilesItemId(42L), "episode.mkv")
        val Token = AccountDownloadToken("download-secret")
    }
}
