package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.putioRequest
import io.putdotio.android.toPutioFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.errors.PutioException
import java.util.concurrent.CancellationException

/** The account's saved position for a media file, which is what "watched" means on put.io. */
interface FilesWatchedRepository {
    /** Saves [seconds] as the file's position; the file's duration marks it watched. */
    suspend fun setPosition(itemId: FilesItemId, seconds: Double): PutioResult<Unit>

    /** Drops the saved position, so the file reads as unwatched. */
    suspend fun clearPosition(itemId: FilesItemId): PutioResult<Unit>
}

class SdkFilesWatchedRepository(
    private val client: PutioClient,
) : FilesWatchedRepository {
    override suspend fun setPosition(itemId: FilesItemId, seconds: Double): PutioResult<Unit> =
        putioRequest { client.files.setStartFrom(itemId.value, seconds) }

    override suspend fun clearPosition(itemId: FilesItemId): PutioResult<Unit> =
        putioRequest { client.files.resetStartFrom(itemId.value) }
}

/**
 * URLs another player can open. They carry the account's download token, never the session's
 * access token, and must never be logged or stored.
 */
fun interface FilesStreamUrls {
    suspend fun originalStreamUrl(itemId: FilesItemId): FilesStreamUrlResult
}

sealed interface FilesStreamUrlResult {
    class Ready(
        val url: String,
    ) : FilesStreamUrlResult {
        override fun toString(): String = "Ready(<redacted stream url>)"
    }

    /** put.io returned no download token; the session's access token is never a substitute. */
    data object DownloadTokenUnavailable : FilesStreamUrlResult

    data class Failure(
        val failure: PutioFailure,
    ) : FilesStreamUrlResult
}

class SdkFilesStreamUrls internal constructor(
    private val loadAccount: suspend () -> AccountInfo,
    private val buildOriginalStreamUrl: (fileId: Long, downloadToken: AccountDownloadToken) -> String,
) : FilesStreamUrls {
    constructor(client: PutioClient) : this(
        loadAccount = client::loadMediaAccount,
        buildOriginalStreamUrl = { fileId, token -> client.files.buildOriginalStreamUrl(fileId, token) },
    )

    // This SDK boundary converts unexpected implementation failures into the app's stable failure taxonomy.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun originalStreamUrl(itemId: FilesItemId): FilesStreamUrlResult =
        try {
            val token = loadAccount().downloadToken
            if (token == null) {
                FilesStreamUrlResult.DownloadTokenUnavailable
            } else {
                FilesStreamUrlResult.Ready(buildOriginalStreamUrl(itemId.value, token))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            FilesStreamUrlResult.Failure(error.toPutioFailure())
        } catch (unexpected: Exception) {
            FilesStreamUrlResult.Failure(PutioFailure.Unexpected(unexpected))
        }
}
