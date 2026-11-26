package io.putdotio.android.documents

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.share.OriginalDownloads
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Response

internal class RemoteOriginals(
    private val http: Call.Factory,
    private val originals: OriginalDownloads,
) {
    fun bytes(fileId: FilesItemId, size: Long): DocumentBytes =
        DocumentBytes(size, onDevice = false) { offset -> open(fileId, offset) }

    private fun open(fileId: FilesItemId, offset: Long): OpenedBytes {
        val download = runBlocking { originals.request(fileId) } ?: throw IOException("No session")
        val request = download.newBuilder()
            .header("Range", "bytes=$offset-")
            .build()
        val call = http.newCall(request)
        val response = call.execute()
        if (response.code == HTTP_UNAUTHORIZED) originals.refused(request)
        return OpenedBytes(response.bodyFrom(offset), interrupt = call::cancel)
    }
}

private fun Response.bodyFrom(offset: Long): InputStream =
    try {
        when {
            code == HTTP_PARTIAL_CONTENT && rangeStart() == offset -> body.byteStream()
            code == HTTP_OK -> body.byteStream().apply { skipFully(offset) }
            else -> throw IOException("Download failed with $code")
        }
    } catch (error: IOException) {
        close()
        throw error
    }

private fun Response.rangeStart(): Long? =
    header("Content-Range")?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()

private const val HTTP_OK = 200
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_PARTIAL_CONTENT = 206
