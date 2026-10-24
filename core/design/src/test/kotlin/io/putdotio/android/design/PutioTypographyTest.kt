package io.putdotio.android.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values come from DESIGN.md's typography front matter and its Typography section. */
class PutioTypographyTest {
    @Test
    fun materialSlotsStockComponentsReadCarryTheContract() {
        val type = putioTypography
        assertStyle(type.titleLarge, size = 22, lineHeight = 28, weight = 500) // top app bar
        assertStyle(type.headlineSmall, size = 22, lineHeight = 28, weight = 500) // dialog title
        assertStyle(type.bodyLarge, size = 16, lineHeight = 22, weight = 400) // list headline
        assertStyle(type.bodyMedium, size = 14, lineHeight = 20, weight = 400) // dialog and snackbar body
        assertStyle(type.labelMedium, size = 12, lineHeight = 16, weight = 500) // nav label
    }

    @Test
    fun noSlotSetsTextBelowTwelveSp() {
        val type = putioTypography
        listOf(
            type.displayLarge, type.displayMedium, type.displaySmall,
            type.headlineLarge, type.headlineMedium, type.headlineSmall,
            type.titleLarge, type.titleMedium, type.titleSmall,
            type.bodyLarge, type.bodyMedium, type.bodySmall,
            type.labelLarge, type.labelMedium, type.labelSmall,
        ).forEach { assertTrue("${it.fontSize}", it.fontSize >= 12.sp) }
    }

    @Test
    fun monoKeepsItsSizeWithTabularFiguresAndAFloor() {
        val meta = monoNumbers(TextStyle(fontSize = 13.sp, lineHeight = 18.sp))
        assertEquals(FontFamily.Monospace, meta.fontFamily)
        assertEquals("\"tnum\"", meta.fontFeatureSettings)
        assertEquals(13.sp, meta.fontSize)
        assertEquals(12.sp, monoNumbers(TextStyle(fontSize = 11.sp)).fontSize)
        assertEquals(12.sp, monoNumbers(TextStyle(fontSize = 0.5.em), inheritedSize = 16.sp).fontSize)
        assertEquals(14.sp, monoNumbers(TextStyle(fontSize = 1.em), inheritedSize = 14.sp).fontSize)
    }

    private fun assertStyle(style: TextStyle, size: Int, lineHeight: Int, weight: Int) {
        assertEquals(FontFamily.Default, style.fontFamily)
        assertEquals(size.sp, style.fontSize)
        assertEquals(lineHeight.sp, style.lineHeight)
        assertEquals(FontWeight(weight), style.fontWeight)
        assertEquals(0.sp, style.letterSpacing)
    }
}
