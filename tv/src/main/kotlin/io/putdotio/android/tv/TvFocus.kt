package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.PutioDesignTokens

/**
 * DESIGN.md › Focus: one focus style, a `--text` fill under dark content, and nothing scales.
 * The TV scheme's inverse pair carries the fill, so stock components take it; these name it
 * for components that draw their own.
 */
internal object TvFocus {
    val container: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.inverseSurface

    val content: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.inverseOnSurface
}

/**
 * Yellow content, such as a row's file glyph: [PutioDesignTokens.yellowSolid] at rest, and the focus
 * content colour while its row is [focused], since nothing yellow sits on the focus fill.
 */
@Composable
@ReadOnlyComposable
internal fun tvAccent(focused: Boolean): Color = if (focused) TvFocus.content else PutioDesignTokens.yellowSolid
