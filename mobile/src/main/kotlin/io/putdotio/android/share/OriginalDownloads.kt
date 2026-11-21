package io.putdotio.android.share

import io.putdotio.android.auth.MediaDownloadToken
import io.putdotio.android.downloads.OAUTH_TOKEN_QUERY
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.errors.PutioException
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Requests for a file's original bytes: the SDK's download URL without its query token, and the account
 * download token in the header. A refused token is forgotten so the next request loads a fresh one.
 */
internal class OriginalDownloads(
    private val token: MediaDownloadToken,
    private val downloadUrl: (FilesItemId, AccountDownloadToken) -> String,
) {
    /** Null without a session; an account put.io cannot read fails as an [IOException]. */
    suspend fun request(fileId: FilesItemId): Request? {
        val downloadToken = try {
            token.get()
        } catch (error: PutioException) {
            throw IOException("The download token is unavailable", error)
        } ?: return null
        val url = downloadUrl(fileId, downloadToken).toHttpUrl().newBuilder()
            .removeAllQueryParameters(OAUTH_TOKEN_QUERY)
            .build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Token ${downloadToken.value}")
            .tag(AccountDownloadToken::class.java, downloadToken)
            .build()
    }

    fun refused(request: Request) {
        request.tag(AccountDownloadToken::class.java)?.let(token::refuse)
    }

    companion object {
        fun from(client: PutioClient, token: MediaDownloadToken): OriginalDownloads =
            OriginalDownloads(token) { fileId, downloadToken -> client.files.buildDownloadUrl(fileId.value, downloadToken) }
    }
}
