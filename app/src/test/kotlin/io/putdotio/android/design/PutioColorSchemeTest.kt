package io.putdotio.android.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the generated scheme to @putdotio/design 3.0.0. A failure here means
 * either the codegen regressed or the vendored token graph changed values —
 * both deliberate events that should update these literals consciously.
 */
class PutioColorSchemeTest {

    @Test
    fun tokensMatchDesign300() {
        assertEquals(Color(0xFFFDCE45), PutioDesignTokens.yellowSolid)
        assertEquals(Color(0xFF2A1E09), PutioDesignTokens.primaryForeground)
        assertEquals(Color(0xFF161616), PutioDesignTokens.appBg)
        assertEquals(Color(0xFFEDEDED), PutioDesignTokens.text)
        assertEquals(Color(0xFFA0A0A0), PutioDesignTokens.textSecondary)
        assertEquals(Color(0xFF232323), PutioDesignTokens.componentBg)
        assertEquals(Color(0xFF282828), PutioDesignTokens.componentBgHover)
        assertEquals(Color(0xFF2E2E2E), PutioDesignTokens.componentBgActive)
        assertEquals(Color(0xFF3E3E3E), PutioDesignTokens.border)
        assertEquals(Color(0xFF343434), PutioDesignTokens.line)
        assertEquals(Color(0xFFE5484D), PutioDesignTokens.redSolid)
    }

    @Test
    fun m3RolesCarryTheContract() {
        val scheme = putioDarkColorScheme()
        assertEquals(PutioDesignTokens.yellowSolid, scheme.primary)
        assertEquals(PutioDesignTokens.primaryForeground, scheme.onPrimary)
        assertEquals(PutioDesignTokens.appBg, scheme.surface)
        assertEquals(PutioDesignTokens.text, scheme.onSurface)
        assertEquals(PutioDesignTokens.textSecondary, scheme.onSurfaceVariant)
        assertEquals(PutioDesignTokens.componentBg, scheme.surfaceContainer)
        assertEquals(PutioDesignTokens.componentBgHover, scheme.surfaceContainerHigh)
        assertEquals(PutioDesignTokens.componentBgActive, scheme.surfaceContainerHighest)
        assertEquals(PutioDesignTokens.border, scheme.outline)
        assertEquals(PutioDesignTokens.line, scheme.outlineVariant)
        assertEquals(PutioDesignTokens.redSolid, scheme.error)
        // Derived: the stock FAB reads primaryContainer and the nav bar
        // indicator reads secondaryContainer; without these they fall back
        // to M3 baseline purple.
        assertEquals(PutioDesignTokens.yellowSolid, scheme.primaryContainer)
        assertEquals(PutioDesignTokens.yellowSolid.copy(alpha = 0.26f), scheme.secondaryContainer)
        assertEquals(PutioDesignTokens.yellowSolid, scheme.onSecondaryContainer)
    }

    @Test
    fun tvRolesCarryTheContract() {
        val scheme = putioTvDarkColorScheme()
        assertEquals(PutioDesignTokens.yellowSolid, scheme.primary)
        assertEquals(PutioDesignTokens.appBg, scheme.background)
        assertEquals(PutioDesignTokens.border, scheme.border)
        assertEquals(PutioDesignTokens.line, scheme.borderVariant)
    }
}
