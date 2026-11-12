package io.putdotio.android.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilesPdfDocumentFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun bytesThatStopArrivingFailAsALoadAndATooLargeDocumentAsUnreadable() {
        val dropped = object : InputStream() {
            override fun read(): Int = throw IOException("Connection reset")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Connection reset")
        }

        val load = runBlocking { runCatching { FilesPdfDocument.open(context, sizeBytes = 1L) { dropped } } }
        val tooLarge = runBlocking {
            runCatching { FilesPdfDocument.open(context, sizeBytes = Long.MAX_VALUE) { "%PDF".byteInputStream() } }
        }

        assertTrue(load.exceptionOrNull() is IOException)
        assertFalse(load.exceptionOrNull() is FilesPdfUnreadable)
        assertTrue(tooLarge.exceptionOrNull() is FilesPdfUnreadable)
    }
}
