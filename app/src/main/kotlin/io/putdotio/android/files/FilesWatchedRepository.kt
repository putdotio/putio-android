package io.putdotio.android.files

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import java.util.concurrent.CancellationException

/** The account's saved position for a media file, which is what "watched" means on put.io. */
interface FilesWatchedRepository {
    /** Saves [seconds] as the file's position; the file's duration marks it watched. */
    suspend fun setPosition(itemId: FilesItemId, seconds: Double): FilesRepositoryResult<Unit>

    /** Drops the saved position, so the file reads as unwatched. */
    suspend fun clearPosition(itemId: FilesItemId): FilesRepositoryResult<Unit>
}

class SdkFilesWatchedRepository(
    private val client: PutioClient,
) : FilesWatchedRepository {
    override suspend fun setPosition(itemId: FilesItemId, seconds: Double): FilesRepositoryResult<Unit> =
        request { client.files.setStartFrom(itemId.value, seconds) }

    override suspend fun clearPosition(itemId: FilesItemId): FilesRepositoryResult<Unit> =
        request { client.files.resetStartFrom(itemId.value) }

    // This SDK boundary converts unexpected implementation failures into the app's stable failure taxonomy.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun request(block: suspend () -> Unit): FilesRepositoryResult<Unit> =
        try {
            block()
            FilesRepositoryResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            FilesRepositoryResult.Failure(error.toFilesFailure())
        } catch (unexpected: Exception) {
            FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
        }
}

/** URLs another player can open; they carry the session's token and must never be logged or stored. */
fun interface FilesStreamUrls {
    /** The original file's stream URL, or null while no session token is set. */
    fun originalStreamUrl(itemId: FilesItemId): String?
}

class SdkFilesStreamUrls(
    private val client: PutioClient,
) : FilesStreamUrls {
    override fun originalStreamUrl(itemId: FilesItemId): String? =
        client.config.accessToken?.let { client.files.buildOriginalStreamUrl(itemId.value, it) }
}
