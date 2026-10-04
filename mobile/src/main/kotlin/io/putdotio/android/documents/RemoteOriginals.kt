package io.putdotio.android.documents

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.share.MobileFileShareService
import java.io.IOException
import java.io.InputStream
import okhttp3.Call
import okhttp3.Response

/**
 * A document's original bytes from put.io, through the request share-out exports with: the API's download endpoint,
 * the session in the header and never in the URL. Each open asks for the rest of the file from its offset.
 */
internal class RemoteOriginals(
    private val http: Call.Factory,
    private val accessToken: () -> String?,
) {
    fun bytes(fileId: FilesItemId, size: Long): DocumentBytes =
        DocumentBytes(size, onDevice = false) { offset -> open(fileId, offset) }

    private fun open(fileId: FilesItemId, offset: Long): OpenedBytes {
        val token = accessToken() ?: throw IOException("No session")
        val request = MobileFileShareService.downloadRequest(fileId, token).newBuilder()
            // A range also stops OkHttp from unzipping the body, so offsets count the file's own bytes.
            .header("Range", "bytes=$offset-")
            .build()
        val call = http.newCall(request)
        return OpenedBytes(call.execute().bodyFrom(offset), interrupt = call::cancel)
    }
}

/** The body from [offset]; a server that ignores the range sends the whole file, which is skipped to it. */
private fun Response.bodyFrom(offset: Long): InputStream =
    try {
        when {
            code == HTTP_PARTIAL_CONTENT && rangeStart() == offset -> body.byteStream()
            code == HTTP_OK -> body.byteStream().apply { skipFully(offset) }
            code == HTTP_UNAUTHORIZED -> throw DownloadUnauthorizedException()
            else -> throw IOException("Download failed with $code")
        }
    } catch (error: IOException) {
        close()
        throw error
    }

/** The first byte of a `Content-Range: bytes <start>-<end>/<total>` answer. */
private fun Response.rangeStart(): Long? =
    header("Content-Range")?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()

/** put.io refused the session's token on the download endpoint. */
internal class DownloadUnauthorizedException : IOException("put.io rejected the session")

private const val HTTP_OK = 200
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_PARTIAL_CONTENT = 206
