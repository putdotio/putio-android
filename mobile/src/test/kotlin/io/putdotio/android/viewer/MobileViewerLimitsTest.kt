package io.putdotio.android.viewer

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileViewerLimitsTest {
    @Test
    fun aShortFileReadsWholeWithItsByteOrderMarkDroppedAndEveryLineEndingSplit() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "1\r\n00:00\rHi\nçay".toByteArray()

        val preview = ByteArrayInputStream(bytes).readTextPreview(limitBytes = 64)

        assertEquals(listOf("1", "00:00", "Hi", "çay"), preview.lines)
        assertFalse(preview.truncated)
    }

    @Test
    fun aFileLongerThanTheLimitStopsThereAndDropsTheCharacterTheLimitCutsInHalf() {
        // "ab" then "ç" (two bytes) straddling a three-byte limit, then more text.
        val bytes = "abçdef".toByteArray()

        val preview = ByteArrayInputStream(bytes).readTextPreview(limitBytes = 3)

        assertEquals(listOf("ab"), preview.lines)
        assertTrue(preview.truncated)
    }

    @Test
    fun aFileExactlyAtTheLimitIsWholeAndMalformedBytesReadAsReplacements() {
        val bytes = "ab".toByteArray() + byteArrayOf(0xFF.toByte())

        val preview = ByteArrayInputStream(bytes).readTextPreview(limitBytes = 3)

        assertEquals(listOf("ab" + Char(0xFFFD)), preview.lines)
        assertFalse(preview.truncated)
    }

    @Test
    fun aPageRendersAtTheShownWidthUnlessThatWouldPassThePixelCap() {
        assertEquals(PdfBitmapSize(1080, 1528), pdfBitmapSize(pageWidth = 595, pageHeight = 842, widthPx = 1080))

        val poster = pdfBitmapSize(pageWidth = 1000, pageHeight = 10_000, widthPx = 2560)
        assertTrue(poster.width.toLong() * poster.height <= 4_000_000L)
        assertEquals(10.0, poster.height.toDouble() / poster.width, 0.05)
    }
}
