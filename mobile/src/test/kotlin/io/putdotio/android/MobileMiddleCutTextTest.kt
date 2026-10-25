package io.putdotio.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One unit of width per UTF-16 code unit, so each case states its own budget. */
class MobileMiddleCutTextTest {
    private val width: (String) -> Int = { it.length }

    @Test
    fun aNameThatFitsStaysWhole() {
        assertEquals("Sintel.mkv", middleCut("Sintel.mkv", 10, width))
    }

    @Test
    fun aLongNameKeepsItsStartAndItsLastElevenCharacters() {
        assertEquals("Blue.Lights.S04E01…ORGANiC.mkv", middleCut(NAME, 30, width))
    }

    @Test
    fun aNarrowRowKeepsAShorterTailRatherThanLoseTheExtension() {
        assertEquals("…ANiC.mkv", middleCut(NAME, 9, width))
    }

    @Test
    fun noCutSplitsAnEmoji() {
        // The last eleven UTF-16 units start inside the first tree.
        val name = "Holiday 🎄 clips 🎄🎄🎄🎄.mp4"
        for (budget in 4..name.length) {
            val cut = middleCut(name, budget, width)
            assertTrue(cut, cut.indices.all { index -> cut.pairedAt(index) })
        }
    }

    private fun String.pairedAt(index: Int): Boolean = when {
        this[index].isHighSurrogate() -> index + 1 < length && this[index + 1].isLowSurrogate()
        this[index].isLowSurrogate() -> index > 0 && this[index - 1].isHighSurrogate()
        else -> true
    }

    private companion object {
        const val NAME = "Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC.mkv"
    }
}
