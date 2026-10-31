package io.putdotio.android.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PutioTvTypographyTest {
    @Test
    fun slotsStockTvComponentsReadCarryTheTvRoles() {
        val type = putioTvTypography
        assertRole(PutioTypeScale.tvBody, type.titleMedium)
        assertRole(PutioTypeScale.tvMeta, type.bodySmall)
        assertRole(PutioTypeScale.tvTitle, type.headlineSmall)
        assertRole(PutioTypeScale.tvDetailTitle, type.displaySmall)
        assertRole(PutioTypeScale.tvLabel, type.labelSmall)
        assertEquals(PutioTypeScale.tvBody.fontSize, type.labelLarge.fontSize)
        assertEquals(PutioTypeScale.tvBody.lineHeight, type.labelLarge.lineHeight)
        assertEquals(PutioTypeScale.tvLabel.fontWeight, type.labelLarge.fontWeight)
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

    private fun assertRole(role: TextStyle, slot: TextStyle) {
        assertEquals(role.fontSize, slot.fontSize)
        assertEquals(role.lineHeight, slot.lineHeight)
        assertEquals(role.fontWeight, slot.fontWeight)
    }
}
