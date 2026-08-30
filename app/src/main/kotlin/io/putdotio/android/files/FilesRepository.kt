package io.putdotio.android.files

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.errors.PutioTransportException
import io.putdotio.sdk.files.FilesListResponse
import io.putdotio.sdk.files.PutioFile
import java.util.concurrent.CancellationException

sealed interface FilesRepositoryResult<out T> {
    data class Success<T>(
        val value: T,
    ) : FilesRepositoryResult<T>

    data class Failure(
        val failure: FilesFailure,
    ) : FilesRepositoryResult<Nothing>
}

sealed interface FilesFailure {
    val cause: Throwable

    data class AuthenticationRequired(
        override val cause: PutioException,
    ) : FilesFailure

    data class AccessDenied(
        override val cause: PutioException,
    ) : FilesFailure

    data class RateLimited(
        override val cause: PutioException,
    ) : FilesFailure

    data class ServerUnavailable(
        val statusCode: Int,
        override val cause: PutioException,
    ) : FilesFailure

    data class ApiRejected(
        val statusCode: Int,
        val errorType: String?,
        override val cause: PutioException,
    ) : FilesFailure

    data class NetworkUnavailable(
        override val cause: PutioException,
    ) : FilesFailure

    data class InvalidResponse(
        override val cause: PutioException,
    ) : FilesFailure

    data class Misconfigured(
        override val cause: PutioException,
    ) : FilesFailure

    data class Unexpected(
        override val cause: Throwable,
    ) : FilesFailure
}

interface FilesRepository {
    suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage>

    suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage>
}

interface FilesItemResolver {
    suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem>
}

class SdkFilesRepository internal constructor(
    private val listFolder: suspend (Long) -> FilesListResponse,
    private val continueListing: suspend (String) -> FilesListResponse,
    private val getFile: suspend (Long) -> PutioFile,
) : FilesRepository, FilesItemResolver {
    constructor(client: PutioClient) : this(
        listFolder = { folderId -> client.files.list(parentId = folderId) },
        continueListing = { cursor -> client.files.continueList(cursor = cursor) },
        getFile = { fileId -> client.files.get(fileId) },
    )

    override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
        requestPage { listFolder(folderId.value) }

    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
        requestPage { continueListing(cursor.value) }

    override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> =
        request { getFile(itemId.value).toFilesItem() }

    // Kotlin/JVM has no typed throws contract, so the SDK boundary converts
    // unknown failures after preserving cancellation.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun requestPage(request: suspend () -> FilesListResponse): FilesRepositoryResult<FilesPage> =
        request { request().toFilesPage() }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> request(request: suspend () -> T): FilesRepositoryResult<T> =
        try {
            FilesRepositoryResult.Success(request())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            FilesRepositoryResult.Failure(error.toFilesFailure())
        } catch (unexpected: Exception) {
            FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
        }
}

private fun FilesListResponse.toFilesPage(): FilesPage =
    FilesPage(
        items = files.map(PutioFile::toFilesItem),
        nextCursor = cursor?.takeIf(String::isNotBlank)?.let(::FilesCursor),
    )

internal fun PutioFile.toFilesItem(): FilesItem =
    FilesItem(
        id = FilesItemId(id),
        parentId = parentId?.let(::FilesItemId),
        name = name,
        type = fileType,
        sizeBytes = size,
        createdAt = createdAt,
    )

// Mirrors PutioAuthSessionGateway.isAuthoritativeAuthRejection: a contract-derived
// 401/403 reason is an auth verdict even when the underlying error is not an API
// exception, and the wrapper chain is walked with a cycle guard.
internal fun PutioException.toFilesFailure(): FilesFailure {
    var current: PutioException = this
    val visited = mutableSetOf<PutioException>()
    while (current is PutioOperationException && visited.add(current)) {
        current.reasonFailure(context = this)?.let { return it }
        current = current.underlyingError
    }
    return current.leafFailure(context = this)
}

private fun PutioOperationException.reasonFailure(context: PutioException): FilesFailure? =
    when ((reason as? PutioOperationErrorReason.StatusCode)?.statusCode) {
        HTTP_UNAUTHORIZED -> FilesFailure.AuthenticationRequired(context)
        HTTP_FORBIDDEN -> FilesFailure.AccessDenied(context)
        else -> null
    }

private fun PutioException.leafFailure(context: PutioException): FilesFailure =
    when (this) {
        is PutioApiException ->
            when (statusCode) {
                HTTP_UNAUTHORIZED -> FilesFailure.AuthenticationRequired(context)
                HTTP_FORBIDDEN -> FilesFailure.AccessDenied(context)
                HTTP_TOO_MANY_REQUESTS -> FilesFailure.RateLimited(context)
                in HTTP_SERVER_ERROR_RANGE -> FilesFailure.ServerUnavailable(statusCode, context)
                else -> FilesFailure.ApiRejected(statusCode, errorType, context)
            }

        is PutioTransportException -> FilesFailure.NetworkUnavailable(context)
        is PutioSerializationException -> FilesFailure.InvalidResponse(context)
        is PutioConfigurationException -> FilesFailure.Misconfigured(context)
        is PutioOperationException -> FilesFailure.Unexpected(context)
    }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599
