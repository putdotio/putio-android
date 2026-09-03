package io.putdotio.android.playback

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountInfoQuery
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.errors.PutioTransportException
import io.putdotio.sdk.files.NextFile
import io.putdotio.sdk.files.NextFileType
import io.putdotio.sdk.files.PlaybackMediaCredential
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PlaybackRequest
import java.util.concurrent.CancellationException

sealed interface PlaybackRepositoryResult<out T> {
    data class Success<T>(
        val value: T,
    ) : PlaybackRepositoryResult<T>

    data class Failure(
        val failure: PlaybackFailure,
    ) : PlaybackRepositoryResult<Nothing>
}

sealed interface PlaybackFailure {
    val cause: Throwable

    data class AuthenticationRequired(
        override val cause: PutioException,
    ) : PlaybackFailure

    data class AccessDenied(
        override val cause: PutioException,
    ) : PlaybackFailure

    data class RateLimited(
        override val cause: PutioException,
    ) : PlaybackFailure

    data class ServerUnavailable(
        val statusCode: Int,
        override val cause: PutioException,
    ) : PlaybackFailure

    data class ApiRejected(
        val statusCode: Int,
        val errorType: String?,
        override val cause: PutioException,
    ) : PlaybackFailure

    data class NetworkUnavailable(
        override val cause: Throwable,
    ) : PlaybackFailure

    data class InvalidResponse(
        override val cause: PutioException,
    ) : PlaybackFailure

    data class MediaCredentialUnavailable(
        override val cause: Throwable,
    ) : PlaybackFailure

    data class Misconfigured(
        override val cause: PutioException,
    ) : PlaybackFailure

    data class Unexpected(
        override val cause: Throwable,
    ) : PlaybackFailure
}

interface PlaybackRepository {
    suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution>

    suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult
}

sealed interface PlaybackNextResult {
    data class Found(
        val target: PlaybackTarget,
    ) : PlaybackNextResult

    data object Ended : PlaybackNextResult

    data class Failure(
        val failure: PlaybackFailure,
    ) : PlaybackNextResult
}

class SdkPlaybackRepository internal constructor(
    private val playbackPreference: () -> PlaybackPreference,
    private val loadAccount: suspend () -> AccountInfo,
    private val resolvePlayback: suspend (PlaybackRequest) -> io.putdotio.sdk.files.PlaybackResolution,
    private val lookupNextFile: suspend (Long, NextFileType) -> NextFile = { _, _ ->
        error("Next-video lookup is not configured")
    },
) : PlaybackRepository {
    constructor(
        client: PutioClient,
        playbackPreference: () -> PlaybackPreference,
    ) : this(
        playbackPreference = playbackPreference,
        loadAccount = { client.account.getInfo(AccountInfoQuery(downloadToken = true)) },
        resolvePlayback = client.files::resolvePlayback,
        lookupNextFile = client.files::findNextFile,
    )

    @Suppress("TooGenericExceptionCaught")
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        try {
            val preference = playbackPreference()
            val account = loadAccount()
            val downloadToken = account.downloadToken
                ?: return PlaybackRepositoryResult.Failure(
                    PlaybackFailure.MediaCredentialUnavailable(MissingPlaybackCredentialException()),
                )
            val resolution = resolvePlayback(
                PlaybackRequest(
                    fileId = target.fileId.value,
                    mediaCredential = PlaybackMediaCredential.downloadToken(downloadToken),
                    preference = preference,
                    useStartFrom = account.settings.useStartFrom,
                ),
            )
            PlaybackRepositoryResult.Success(resolution.toAppResolution())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackRepositoryResult.Failure(error.toPlaybackFailure())
        } catch (unexpected: Exception) {
            PlaybackRepositoryResult.Failure(PlaybackFailure.Unexpected(unexpected))
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
        try {
            val next = lookupNextFile(target.fileId.value, NextFileType.VIDEO)
            PlaybackNextResult.Found(
                PlaybackTarget(
                    fileId = io.putdotio.android.files.FilesItemId(next.id),
                    name = next.name,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            if (error.hasHttpStatusCode(HTTP_NOT_FOUND)) {
                PlaybackNextResult.Ended
            } else {
                PlaybackNextResult.Failure(error.toPlaybackFailure())
            }
        } catch (unexpected: Exception) {
            PlaybackNextResult.Failure(PlaybackFailure.Unexpected(unexpected))
        }
}

internal class MissingPlaybackCredentialException : IllegalStateException("Playback credential is unavailable")

private fun io.putdotio.sdk.files.PlaybackResolution.toAppResolution(): PlaybackResolution =
    when (this) {
        is io.putdotio.sdk.files.PlaybackResolution.Ready -> PlaybackResolution.Ready(source)
        is io.putdotio.sdk.files.PlaybackResolution.Conversion -> PlaybackResolution.Conversion(state)
        is io.putdotio.sdk.files.PlaybackResolution.Unsupported -> PlaybackResolution.Unsupported(fileType)
    }

sealed interface PlaybackResolution {
    data class Ready(
        val source: io.putdotio.sdk.files.PlaybackSource,
    ) : PlaybackResolution

    data class Conversion(
        val state: io.putdotio.sdk.files.PlaybackConversionState,
    ) : PlaybackResolution

    data class Unsupported(
        val fileType: io.putdotio.sdk.files.PutioFileType,
    ) : PlaybackResolution
}

private fun PutioException.toPlaybackFailure(): PlaybackFailure {
    var current: PutioException = this
    val visited = mutableSetOf<PutioException>()
    val wrappers = mutableListOf<PutioOperationException>()
    while (current is PutioOperationException && visited.add(current)) {
        wrappers += current
        current = current.underlyingError
    }
    return if (current is PutioApiException) {
        current.leafFailure(context = this)
    } else {
        wrappers.firstNotNullOfOrNull { it.reasonFailure(context = this) }
            ?: current.leafFailure(context = this)
    }
}

private fun PutioException.hasHttpStatusCode(statusCode: Int): Boolean {
    var current: PutioException = this
    val visited = mutableSetOf<PutioException>()
    while (current is PutioOperationException && visited.add(current)) {
        current = current.underlyingError
    }
    return (current as? PutioApiException)?.httpStatusCode == statusCode
}

private fun PutioOperationException.reasonFailure(context: PutioException): PlaybackFailure? =
    when ((reason as? PutioOperationErrorReason.StatusCode)?.statusCode) {
        HTTP_UNAUTHORIZED -> PlaybackFailure.AuthenticationRequired(context)
        HTTP_FORBIDDEN -> PlaybackFailure.AccessDenied(context)
        else -> null
    }

private fun PutioException.leafFailure(context: PutioException): PlaybackFailure =
    when (this) {
        is PutioApiException ->
            when (httpStatusCode) {
                HTTP_UNAUTHORIZED -> PlaybackFailure.AuthenticationRequired(context)
                HTTP_FORBIDDEN -> PlaybackFailure.AccessDenied(context)
                HTTP_TOO_MANY_REQUESTS -> PlaybackFailure.RateLimited(context)
                in HTTP_SERVER_ERROR_RANGE -> PlaybackFailure.ServerUnavailable(httpStatusCode, context)
                else -> PlaybackFailure.ApiRejected(httpStatusCode, errorType, context)
            }

        is PutioTransportException -> PlaybackFailure.NetworkUnavailable(context)
        is PutioSerializationException -> PlaybackFailure.InvalidResponse(context)
        is PutioConfigurationException -> PlaybackFailure.Misconfigured(context)
        is PutioOperationException -> PlaybackFailure.Unexpected(context)
    }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599
