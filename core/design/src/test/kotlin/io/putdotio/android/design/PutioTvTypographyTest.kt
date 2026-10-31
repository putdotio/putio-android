package io.putdotio.android.design

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
import org.junit.Test

class PutioTvTypographyTest {
    @Test
    fun noSlotGoesBelowTwelveSp() {
        with(putioTvTypography) {
            listOf(
                displayLarge, displayMedium, displaySmall,
                headlineLarge, headlineMedium, headlineSmall,
                titleLarge, titleMedium, titleSmall,
                bodyLarge, bodyMedium, bodySmall,
                labelLarge, labelMedium, labelSmall,
            )
        }.forEach { assertTrue("${it.fontSize}", it.fontSize >= 12.sp) }
        assertTrue(PutioTypeScale.tvMono.fontSize >= 12.sp)
    }
}
