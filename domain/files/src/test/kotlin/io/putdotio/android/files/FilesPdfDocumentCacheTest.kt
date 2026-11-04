package io.putdotio.android.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilesPdfDocumentCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cache = File(context.cacheDir, "viewer")

    @Test
    fun aClearDuringACopyDeletesLeftoversButNotTheCopy() {
        val leftover = File(cache.apply { mkdirs() }, "leftover.pdf").apply { writeText("%PDF") }
        var cachedDuringCopy: List<String>? = null
        val stream = object : InputStream() {
            private var served = false

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (served) return -1
                served = true
                FilesPdfDocument.clearCache(context)
                cachedDuringCopy = cache.list()?.toList().orEmpty()
                buffer[offset] = '%'.code.toByte()
                return 1
            }
        }

        runBlocking { runCatching { FilesPdfDocument.open(context, sizeBytes = 1L) { stream } } }

        assertEquals(1, cachedDuringCopy?.size)
        assertFalse(leftover.name in cachedDuringCopy.orEmpty())
    }
}
