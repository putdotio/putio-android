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
                loadAccount = { throw accountFailure },
                resolvePlayback = { error("Resolver must not run") },
            ).resolve(Target) as PlaybackRepositoryResult.Failure
            val playbackResult = SdkPlaybackRepository(
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
                    loadAccount = { throw cancellation },
                    resolvePlayback = { error("Resolver must not run") },
                ).resolve(Target)
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

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
