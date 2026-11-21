package io.putdotio.android.share

import io.putdotio.android.auth.MediaDownloadToken
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.files.FilesApi
import java.io.IOException
import okhttp3.Request

/**
 * Requests for a file's original bytes, as the SDK builds them: a token-free URL with the account download token in
 * the header. A refused token is forgotten so the next request loads a fresh one.
 */
internal class OriginalDownloads(
    private val session: () -> MediaDownloadToken?,
    private val files: FilesApi,
) {
    constructor(token: MediaDownloadToken, files: FilesApi) : this({ token }, files)

    /** Null without a session; an account put.io cannot read fails as an [IOException]. */
    suspend fun request(fileId: FilesItemId): Request? {
        val downloadToken = session()?.let { holder ->
            try {
                holder.get()
            } catch (error: PutioException) {
                throw IOException("The download token is unavailable", error)
            }
        } ?: return null
        val media = files.buildDownloadRequest(fileId.value, downloadToken)
        return Request.Builder()
            .url(media.url)
            .apply { media.headers.forEach(::header) }
            .tag(AccountDownloadToken::class.java, downloadToken)
            .build()
    }

    fun refused(request: Request) {
        val token = request.tag(AccountDownloadToken::class.java) ?: return
        session()?.refuse(token)
    }

    companion object {
        fun from(client: PutioClient, session: () -> MediaDownloadToken?): OriginalDownloads =
            OriginalDownloads(session, client.files)
    }
}
