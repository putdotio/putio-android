package io.putdotio.android.share

import android.content.Context
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.files.FilesItemId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

internal const val EXPORT_PROGRESS_MAX = 100

private const val BUFFER_SIZE = 64 * 1024

/** Downloads one original file into its session's share directory, replacing every earlier export. */
internal class MobileShareExporter(
    private val context: Context,
    private val dependencies: MobileShareDependencies,
) {
    /** [onProgress] receives each new whole percent on the IO dispatcher, only when the size is known. */
    suspend fun export(
        fileId: FilesItemId,
        name: String,
        session: MobileAuthSessionId,
        onProgress: (Int) -> Unit,
    ): File = withContext(dependencies.io) {
        // Unlinking an open file is safe on Android; a recipient still reading keeps its descriptor.
        MobileFileShareService.shareRoot(context).deleteRecursively()
        val directory = File(MobileFileShareService.sessionShares(context, session), fileId.value.toString())
        directory.mkdirs()
        var complete = false
        try {
            val target = File(directory, name.sanitizedFileName())
            val token = dependencies.accessToken() ?: throw IOException("No session")
            val request = MobileFileShareService.downloadRequest(fileId, token)
            dependencies.http.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) throw IOException("Download failed with ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                body.byteStream().copyWithProgress(target, body.contentLength(), onProgress)
            }
            complete = true
            target
        } finally {
            if (!complete) directory.deleteRecursively()
        }
    }
}

/**
 * Runs the blocking [Call] and [block] on its response; cancelling the coroutine cancels the call at once,
 * even while it blocks in `execute()` or a body read. A completion handler would wait for that block to end.
 * The watcher resumes on another thread of the caller's dispatcher, so that dispatcher must not be single-threaded.
 */
private suspend fun <T> Call.executeCancellable(block: suspend (Response) -> T): T = coroutineScope {
    val call = this@executeCancellable
    val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
        // Cancelling a call that already finished is a no-op.
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        call.execute().use { block(it) }
    } finally {
        canceller.cancel()
    }
}

private suspend fun InputStream.copyWithProgress(target: File, total: Long, onProgress: (Int) -> Unit) {
    use { input -> target.outputStream().use { output -> input.streamTo(output, total, onProgress) } }
}

private suspend fun InputStream.streamTo(output: OutputStream, total: Long, onProgress: (Int) -> Unit) {
    val buffer = ByteArray(BUFFER_SIZE)
    var copied = 0L
    var lastPercent = -1
    var read = readUnlessCancelled(buffer)
    while (read >= 0) {
        output.write(buffer, 0, read)
        copied += read
        // An unknown size keeps the last percent, so nothing is reported.
        val percent = if (total > 0L) (copied * EXPORT_PROGRESS_MAX / total).toInt() else lastPercent
        if (percent != lastPercent) {
            lastPercent = percent
            currentCoroutineContext().ensureActive()
            onProgress(percent)
        }
        read = readUnlessCancelled(buffer)
    }
}

private suspend fun InputStream.readUnlessCancelled(buffer: ByteArray): Int {
    currentCoroutineContext().ensureActive()
    return read(buffer)
}
