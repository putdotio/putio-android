package io.putdotio.android.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

/** A PDF the viewer can't show however often it tries: absent, too large, protected or malformed. */
public class FilesPdfUnreadable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One PDF open in a viewer. The renderer needs a seekable file, and a stream from put.io reopens the
 * connection on every backward seek, so the document is first copied, in one pass, into a private
 * cache file that [close] deletes. Pages render one at a time, at the width they are shown.
 */
public class FilesPdfDocument private constructor(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val dispatcher: CoroutineDispatcher,
) {
    public val pageCount: Int get() = renderer.pageCount

    /** Renders [index] [widthPx] wide, its height kept to the page's own ratio, on white as paper is. */
    public suspend fun render(index: Int, widthPx: Int): Bitmap = render(index) { width, height ->
        pdfBitmapSize(width, height, widthPx)
    }

    /** Renders [index] as large as fits [widthPx] by [heightPx], its ratio kept. */
    public suspend fun renderFitting(index: Int, widthPx: Int, heightPx: Int): Bitmap = render(index) { width, height ->
        pdfBitmapSize(width, height, pdfFittingWidth(width, height, widthPx, heightPx))
    }

    private suspend fun render(index: Int, size: (Int, Int) -> PdfBitmapSize): Bitmap = withContext(dispatcher) {
        renderer.openPage(index).use { page ->
            val bitmapSize = size(page.width, page.height)
            val bitmap = createBitmap(bitmapSize.width, bitmapSize.height)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /** Closes after any page still rendering, off the caller's thread, and deletes the copy. */
    public fun close() {
        CoroutineScope(dispatcher + NonCancellable).launch {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            file.delete()
        }
    }

    public companion object {
        /**
         * Copies the stream [openStream] returns into the viewer cache and opens it; throws an
         * [IOException] when the bytes don't arrive, and a [FilesPdfUnreadable] when the document is too
         * large, protected, absent or doesn't parse. [openStream] runs off the main thread; null means no bytes.
         */
        public suspend fun open(context: Context, sizeBytes: Long, openStream: () -> InputStream?): FilesPdfDocument {
            if (sizeBytes > PDF_MAX_BYTES) throw FilesPdfUnreadable("Too large to view in the app")
            // One renderer may run one page at a time, so every call on it shares one thread.
            val dispatcher = Dispatchers.IO.limitedParallelism(1)
            // A caller cancelled as the document opens never receives it, so it is closed here instead.
            val opened = AtomicReference<FilesPdfDocument?>(null)
            try {
                return withContext(dispatcher) {
                    val file = File(viewerCache(context).apply { mkdirs() }, "${UUID.randomUUID()}.pdf")
                    copying += file
                    try {
                        val descriptor = try {
                            copyInto(openStream, file)
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        } finally {
                            copying -= file
                        }
                        FilesPdfDocument(file, descriptor, openRenderer(descriptor), dispatcher)
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

        private suspend fun copyInto(openStream: () -> InputStream?, file: File) {
            val input = openStream() ?: throw FilesPdfUnreadable("No stream")
            input.use { source -> file.outputStream().use { source.copyCancellably(it) } }
        }

        /** The renderer over [descriptor], which closes when the document doesn't parse or is protected. */
        private fun openRenderer(descriptor: ParcelFileDescriptor): PdfRenderer =
            try {
                PdfRenderer(descriptor)
            } catch (error: IOException) {
                descriptor.close()
                throw FilesPdfUnreadable("Unreadable document", error)
            } catch (error: SecurityException) {
                // A password-protected document.
                descriptor.close()
                throw FilesPdfUnreadable("Protected document", error)
            }

        /**
         * Deletes copies left by a viewer that never closed, a crash say, and those of open documents,
         * which keep reading through their descriptors. A copy still being written stays.
         */
        public fun clearCache(context: Context) {
            viewerCache(context).listFiles()?.filterNot { it in copying }?.forEach { it.deleteRecursively() }
        }

        private val copying: MutableSet<File> = ConcurrentHashMap.newKeySet()

        private fun viewerCache(context: Context): File = File(context.cacheDir, "viewer")
    }
}

private suspend fun InputStream.copyCancellably(output: java.io.OutputStream) {
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

/** The width at which a page fills [widthPx] by [heightPx] without passing either side. */
internal fun pdfFittingWidth(pageWidth: Int, pageHeight: Int, widthPx: Int, heightPx: Int): Int {
    val ratio = pageHeight.toDouble() / pageWidth.coerceAtLeast(1)
    val byHeight = (heightPx.coerceAtLeast(1) / ratio).toInt()
    return minOf(widthPx, byHeight).coerceAtLeast(1)
}

private const val PDF_MAX_PAGE_RATIO = 20.0

/** About 16 MB per page bitmap at four bytes a pixel. */
private const val PDF_MAX_PIXELS = 4_000_000.0

/** Larger documents are too much to copy into the cache before the first page shows. */
internal const val PDF_MAX_BYTES = 200L * 1000L * 1000L

private const val COPY_BUFFER_BYTES = 64 * 1024
