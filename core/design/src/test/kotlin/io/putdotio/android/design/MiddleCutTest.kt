package io.putdotio.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiddleCutTest {
    private val width: (String) -> Int = { it.length }

    @Test
    fun aNameThatFitsStaysWhole() {
        assertEquals("Sintel.mkv", cut("Sintel.mkv", 10))
    }

    @Test
    fun aLongNameKeepsItsStartAndItsLastElevenCharacters() {
        assertEquals("Blue.Lights.S04E01…ORGANiC.mkv", cut(NAME, 30))
    }

    @Test
    fun aNarrowRowKeepsAShorterTailRatherThanLoseTheExtension() {
        assertEquals("…ANiC.mkv", cut(NAME, 9))
    }

    @Test
    fun noCutSplitsAnEmoji() {
        val name = "Holiday 🎄 clips 🎄🎄🎄🎄.mp4"
        for (budget in 4..name.length) {
            val cut = cut(name, budget)
            assertTrue(cut, cut.indices.all { index -> cut.pairedAt(index) })
        }
    }

    @Test
    fun aPricedCutMatchesTheMeasuredCutAtEveryWidth() {
        val layout = MiddleCutLayout(NAME.length, ellipsisWidth = { 1f }) { it.toFloat() }
        for (budget in 1..NAME.length + 1) {
            assertEquals("width $budget", middleCut(NAME, budget, width), middleCut(NAME, budget, layout, width))
        }
    }

    @Test
    fun aPricedCutThatMeasuresWiderFallsBackToTheMeasuredCut() {
        val kernedJoin: (String) -> Int = { it.length + if ('…' in it) 2 else 0 }
        val layout = MiddleCutLayout(NAME.length, ellipsisWidth = { 1f }) { it.toFloat() }
        for (budget in 1..NAME.length) {
            val priced = middleCut(NAME, budget, layout, kernedJoin)
            assertEquals("width $budget", middleCut(NAME, budget, kernedJoin), priced)
        }
    }

    @Test
    fun aMixedDirectionNameKeepsTheMeasuredCut() {
        val name = "abcdefghij" + "אבגדהוזחטיכלמנסעפצקר" + ".mp4"
        val rtl = 10 until 30
        val layout = MiddleCutLayout(name.length, ellipsisWidth = { 1f }) { offset ->
            (if (offset in rtl) rtl.first + rtl.last + 1 - offset else offset).toFloat()
        }
        for (budget in 1..name.length) {
            assertEquals("width $budget", middleCut(name, budget, width), middleCut(name, budget, layout, width))
        }
    }

    private fun cut(name: String, budget: Int): String =
        middleCut(name, budget, MiddleCutLayout(name.length, ellipsisWidth = { 1f }) { it.toFloat() }, width)

    private fun String.pairedAt(index: Int): Boolean = when {
        this[index].isHighSurrogate() -> index + 1 < length && this[index + 1].isLowSurrogate()
        this[index].isLowSurrogate() -> index > 0 && this[index - 1].isHighSurrogate()
        else -> true
    }

    private companion object {
        const val NAME = "Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC.mkv"
    }
}
