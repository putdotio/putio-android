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

public class FilesPdfUnreadable(message: String, cause: Throwable? = null) : IOException(message, cause)

public class FilesPdfDocument private constructor(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val dispatcher: CoroutineDispatcher,
) {
    public val pageCount: Int get() = renderer.pageCount

    public suspend fun render(index: Int, widthPx: Int): Bitmap = render(index) { width, height ->
        pdfBitmapSize(width, height, widthPx)
    }

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

    public fun close() {
        CoroutineScope(dispatcher + NonCancellable).launch {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            file.delete()
        }
    }

    public companion object {
        public suspend fun open(context: Context, sizeBytes: Long, openStream: () -> InputStream?): FilesPdfDocument {
            if (sizeBytes > PDF_MAX_BYTES) throw FilesPdfUnreadable("Too large to view in the app")
            val dispatcher = Dispatchers.IO.limitedParallelism(1)
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

        private fun openRenderer(descriptor: ParcelFileDescriptor): PdfRenderer =
            try {
                PdfRenderer(descriptor)
            } catch (error: IOException) {
                descriptor.close()
                throw FilesPdfUnreadable("Unreadable document", error)
            } catch (error: SecurityException) {
                descriptor.close()
                throw FilesPdfUnreadable("Protected document", error)
            }

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

internal fun pdfBitmapSize(pageWidth: Int, pageHeight: Int, widthPx: Int): PdfBitmapSize {
    val ratio = pageHeight.toDouble() / pageWidth.coerceAtLeast(1)
    require(ratio <= PDF_MAX_PAGE_RATIO) { "Page too tall to show" }
    var width = widthPx.coerceAtLeast(1).toDouble()
    var height = width * ratio
    val pixels = width * height
    if (pixels > PDF_MAX_PIXELS) {
        val scale = kotlin.math.sqrt(PDF_MAX_PIXELS / pixels)
        width *= scale
        height *= scale
    }
    val finalWidth = width.toInt().coerceAtLeast(1)
    val finalHeight = height.toInt().coerceIn(1, (PDF_MAX_PIXELS / finalWidth).toInt().coerceAtLeast(1))
    return PdfBitmapSize(finalWidth, finalHeight)
}

internal fun pdfFittingWidth(pageWidth: Int, pageHeight: Int, widthPx: Int, heightPx: Int): Int {
    val ratio = pageHeight.toDouble() / pageWidth.coerceAtLeast(1)
    val byHeight = (heightPx.coerceAtLeast(1) / ratio).toInt()
    return minOf(widthPx, byHeight).coerceAtLeast(1)
}

private const val PDF_MAX_PAGE_RATIO = 20.0

private const val PDF_MAX_PIXELS = 4_000_000.0

internal const val PDF_MAX_BYTES = 200L * 1000L * 1000L

private const val COPY_BUFFER_BYTES = 64 * 1024
