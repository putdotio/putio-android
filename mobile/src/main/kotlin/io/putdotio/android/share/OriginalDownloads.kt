package io.putdotio.android.share

import io.putdotio.android.auth.MediaDownloadToken
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.files.FilesApi
import java.io.IOException
import okhttp3.Request

internal class OriginalDownloads(
    private val session: () -> MediaDownloadToken?,
    private val files: FilesApi,
) {
    constructor(token: MediaDownloadToken, files: FilesApi) : this({ token }, files)

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
