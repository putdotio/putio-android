package io.putdotio.android.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values come from DESIGN.md's `tv-*` front matter and its TV type section. */
class PutioTvTypographyTest {
    @Test
    fun slotsStockTvComponentsReadCarryTheTvRoles() {
        val type = putioTvTypography
        assertStyle(type.titleMedium, size = 13, lineHeight = 18, weight = 400)
        assertStyle(type.bodySmall, size = 12, lineHeight = 16, weight = 400)
        assertStyle(type.labelLarge, size = 13, lineHeight = 18, weight = 500)
        assertStyle(type.headlineSmall, size = 22, lineHeight = 28, weight = 500)
        assertStyle(type.displaySmall, size = 18, lineHeight = 24, weight = 500)
        assertStyle(type.labelSmall, size = 12, lineHeight = 16, weight = 500)
    }

    @Test
    fun everySlotSetsGtAmericaAndNoneGoesBelowTwelveSp() {
        slots().forEach {
            assertEquals(PutioFontFamily.gtAmerica, it.fontFamily)
            assertTrue("${it.fontSize}", it.fontSize >= 12.sp)
        }
    }

    private fun slots(): List<TextStyle> = with(putioTvTypography) {
        listOf(
            displayLarge, displayMedium, displaySmall,
            headlineLarge, headlineMedium, headlineSmall,
            titleLarge, titleMedium, titleSmall,
            bodyLarge, bodyMedium, bodySmall,
            labelLarge, labelMedium, labelSmall,
        )
    }

    private fun assertStyle(style: TextStyle, size: Int, lineHeight: Int, weight: Int) {
        assertEquals(size.sp, style.fontSize)
        assertEquals(lineHeight.sp, style.lineHeight)
        assertEquals(FontWeight(weight), style.fontWeight)
    }
}
