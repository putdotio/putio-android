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
import io.putdotio.sdk.files.FileDetailsQuery
import io.putdotio.sdk.files.FilesContinueQuery
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesListResponse
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PlaybackMediaCredential
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PlaybackRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val loadFile: suspend (Long) -> PutioFile = { error("File lookup is not configured") },
    private val listFolder: suspend (Long, FilesListQuery) -> FilesListResponse = { _, _ ->
        error("Folder listing is not configured")
    },
    private val continueListing: suspend (String, FilesContinueQuery) -> FilesListResponse = { _, _ ->
        error("Folder pagination is not configured")
    },
) : PlaybackRepository {
    constructor(
        client: PutioClient,
        playbackPreference: () -> PlaybackPreference,
    ) : this(
        playbackPreference = playbackPreference,
        loadAccount = { client.account.getInfo(AccountInfoQuery(downloadToken = true)) },
        resolvePlayback = client.files::resolvePlayback,
        loadFile = { fileId ->
            client.files.get(
                fileId,
                FileDetailsQuery(mp4Size = false, startFrom = false, streamUrl = false, mp4StreamUrl = false),
            )
        },
        listFolder = client.files::list,
        continueListing = client.files::continueList,
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
            PlaybackRepositoryResult.Success(resolution.toAppResolution(account.settings.useStartFrom))
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
            val current = try {
                loadFile(target.fileId.value)
            } catch (error: PutioException) {
                if (!error.hasHttpStatusCode(HTTP_NOT_FOUND)) throw error
                null
            }
            if (current == null) {
                PlaybackNextResult.Ended
            } else {
                findNextInFolder(target, requireNotNull(current.parentId))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackNextResult.Failure(error.toPlaybackFailure())
        } catch (unexpected: Exception) {
            PlaybackNextResult.Failure(PlaybackFailure.Unexpected(unexpected))
        }

    private suspend fun findNextInFolder(target: PlaybackTarget, parentId: Long): PlaybackNextResult {
        // The next-file endpoint can cross folders and fall back to a random account video.
        // Walk the server's explicit name ordering instead, stopping at this folder's end.
        var page = listFolder(
            parentId,
            FilesListQuery(perPage = AUTOPLAY_PAGE_SIZE, fileType = PutioFileType.VIDEO, sortBy = "NAME_ASC"),
        )
        var foundCurrent = false
        val cursors = mutableSetOf<String>()
        val seenFileIds = mutableSetOf<Long>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val cursor = page.cursor?.takeIf(String::isNotBlank)
            check(cursor == null || cursors.add(cursor)) { "Folder listing repeated its cursor" }
            for (file in page.files) {
                if (file.fileType != PutioFileType.VIDEO ||
                    file.parentId != parentId ||
                    !seenFileIds.add(file.id)
                ) continue
                if (file.id == target.fileId.value) {
                    foundCurrent = true
                } else if (foundCurrent) {
                    return PlaybackNextResult.Found(
                        PlaybackTarget(io.putdotio.android.files.FilesItemId(file.id), file.name),
                    )
                }
            }
            if (cursor == null) break
            page = continueListing(cursor, FilesContinueQuery(perPage = AUTOPLAY_PAGE_SIZE))
        }
        return PlaybackNextResult.Ended
    }
}

private const val AUTOPLAY_PAGE_SIZE = 200

internal class MissingPlaybackCredentialException : IllegalStateException("Playback credential is unavailable")

private fun io.putdotio.sdk.files.PlaybackResolution.toAppResolution(useStartFrom: Boolean): PlaybackResolution =
    when (this) {
        is io.putdotio.sdk.files.PlaybackResolution.Ready -> PlaybackResolution.Ready(source, useStartFrom)
        is io.putdotio.sdk.files.PlaybackResolution.Conversion -> PlaybackResolution.Conversion(state)
        is io.putdotio.sdk.files.PlaybackResolution.Unsupported -> PlaybackResolution.Unsupported(fileType)
    }

sealed interface PlaybackResolution {
    data class Ready(
        val source: io.putdotio.sdk.files.PlaybackSource,
        val useStartFrom: Boolean = false,
    ) : PlaybackResolution

    data class Conversion(
        val state: io.putdotio.sdk.files.PlaybackConversionState,
    ) : PlaybackResolution

    data class Unsupported(
        val fileType: io.putdotio.sdk.files.PutioFileType,
    ) : PlaybackResolution
}

internal fun PutioException.toPlaybackFailure(): PlaybackFailure {
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
