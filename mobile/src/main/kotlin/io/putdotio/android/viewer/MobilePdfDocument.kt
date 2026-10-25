package io.putdotio.android.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One PDF open in the viewer. The renderer needs a seekable file, and the provider's stream from put.io
 * reopens the connection on every backward seek, so the document is first copied, in one pass, into a
 * private cache file that [close] deletes. Pages render one at a time, at the width they are shown.
 */
internal class MobilePdfDocument private constructor(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val dispatcher: CoroutineDispatcher,
) {
    val pageCount: Int get() = renderer.pageCount

    /** Renders [index] [widthPx] wide, its height kept to the page's own ratio, on white as paper is. */
    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(dispatcher) {
        renderer.openPage(index).use { page ->
            val size = pdfBitmapSize(page.width, page.height, widthPx)
            val bitmap = createBitmap(size.width, size.height)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /** Closes after any page still rendering, off the caller's thread, and deletes the copy. */
    fun close() {
        CoroutineScope(dispatcher + NonCancellable).launch {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            file.delete()
        }
    }

    companion object {
        /** Copies [uri] into the viewer cache and opens it; throws when the bytes don't arrive or don't parse. */
        suspend fun open(context: Context, uri: Uri, sizeBytes: Long): MobilePdfDocument {
            if (sizeBytes > PDF_MAX_BYTES) throw IOException("Too large to view in the app")
            // One renderer may run one page at a time, so every call on it shares one thread.
            val dispatcher = Dispatchers.IO.limitedParallelism(1)
            // A caller cancelled as the document opens never receives it, so it is closed here instead.
            val opened = AtomicReference<MobilePdfDocument?>(null)
            try {
                return withContext(dispatcher) {
                    val file = File(viewerCache(context).apply { mkdirs() }, "${UUID.randomUUID()}.pdf")
                    try {
                        copyInto(context, uri, file)
                        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        MobilePdfDocument(file, descriptor, openRenderer(descriptor), dispatcher)
                            .also { opened.set(it) }
                    } finally {
                        if (opened.get() == null) file.delete()
                    }
                }
            } catch (cancelled: CancellationException) {
                opened.get()?.close()
                throw cancelled
            }
        }

        private suspend fun copyInto(context: Context, uri: Uri, file: File) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream")
            input.use { source -> file.outputStream().use { source.copyCancellably(it) } }
        }

        /** The renderer over [descriptor], which closes when the document doesn't parse or is protected. */
        private fun openRenderer(descriptor: ParcelFileDescriptor): PdfRenderer =
            try {
                PdfRenderer(descriptor)
            } catch (error: IOException) {
                descriptor.close()
                throw error
            } catch (error: SecurityException) {
                // A password-protected document.
                descriptor.close()
                throw IOException("Protected document", error)
            }

        /** Copies left by a viewer that never closed, a crash say; a new viewer clears them. */
        fun clearCache(context: Context) {
            viewerCache(context).deleteRecursively()
        }

        private fun viewerCache(context: Context): File = File(context.cacheDir, "viewer")
    }
}

private suspend fun java.io.InputStream.copyCancellably(output: java.io.OutputStream) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var read = read(buffer)
    while (read >= 0) {
        currentCoroutineContext().ensureActive()
        output.write(buffer, 0, read)
        read = read(buffer)
    }
}

internal data class PdfBitmapSize(val width: Int, val height: Int)

/**
 * A page [widthPx] wide at its own ratio, shrunk to stay under [PDF_MAX_PIXELS] so a poster-sized
 * page can't take the heap; never under one pixel a side.
 */
internal fun pdfBitmapSize(pageWidth: Int, pageHeight: Int, widthPx: Int): PdfBitmapSize {
    val ratio = pageHeight.toDouble() / pageWidth.coerceAtLeast(1)
    // A strip taller than this can't be laid out at the frame's width; it fails as a page that won't render.
    require(ratio <= PDF_MAX_PAGE_RATIO) { "Page too tall to show" }
    var width = widthPx.coerceAtLeast(1).toDouble()
    var height = width * ratio
    val pixels = width * height
    if (pixels > PDF_MAX_PIXELS) {
        val scale = kotlin.math.sqrt(PDF_MAX_PIXELS / pixels)
        width *= scale
        height *= scale
    }
    // A sliver of a page keeps one pixel of width, so its height is bounded again from that.
    val finalWidth = width.toInt().coerceAtLeast(1)
    val finalHeight = height.toInt().coerceIn(1, (PDF_MAX_PIXELS / finalWidth).toInt().coerceAtLeast(1))
    return PdfBitmapSize(finalWidth, finalHeight)
}

private const val PDF_MAX_PAGE_RATIO = 20.0

/** About 16 MB per page bitmap at four bytes a pixel. */
private const val PDF_MAX_PIXELS = 4_000_000.0

/** Larger documents open with another app, which can read them without a copy. */
internal const val PDF_MAX_BYTES = 200L * 1000L * 1000L

private const val COPY_BUFFER_BYTES = 64 * 1024
