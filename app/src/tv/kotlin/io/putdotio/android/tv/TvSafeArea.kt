package io.putdotio.android.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import io.putdotio.android.design.PutioDesignTokens
import kotlin.math.ceil

/**
 * Insets content by the TV overscan safe area: the `tv.overscan` token fractions
 * of the viewport this element fills, so 720p, 1080p and 4K panels keep the same
 * proportion clear. Backgrounds drawn before this modifier still reach the edges.
 */
internal fun Modifier.tvOverscanPadding(): Modifier = layout { measurable, constraints ->
    check(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
        "the overscan safe area is a fraction of the viewport; it needs bounded constraints"
    }
    val insetX = tvOverscanInset(constraints.maxWidth, PutioDesignTokens.tvOverscanX)
    val insetY = tvOverscanInset(constraints.maxHeight, PutioDesignTokens.tvOverscanY)
    val placeable = measurable.measure(constraints.offset(horizontal = -2 * insetX, vertical = -2 * insetY))
    layout(placeable.width + 2 * insetX, placeable.height + 2 * insetY) {
        placeable.place(insetX, insetY)
    }
}

/** Rounded up: a partial pixel of inset still belongs to the unsafe edge. */
private fun tvOverscanInset(viewportPx: Int, ratio: Float): Int = ceil(viewportPx * ratio.toDouble()).toInt()
