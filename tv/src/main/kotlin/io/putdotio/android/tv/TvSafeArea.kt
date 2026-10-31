package io.putdotio.android.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.putdotio.android.design.PutioDesignTokens
import kotlin.math.ceil
import kotlin.math.max

/** DESIGN.md › TV layout: layouts keep 48dp at the sides and 24dp at top and bottom. */
internal val TV_SIDE_MARGIN = 48.dp
internal val TV_VERTICAL_MARGIN = 24.dp

/**
 * Insets content by the TV overscan safe area: the `tv.overscan` token fractions
 * of the viewport this element fills, so 720p, 1080p and 4K panels keep the same
 * proportion clear. Backgrounds drawn before this modifier still reach the edges.
 */
internal fun Modifier.tvOverscanPadding(): Modifier = tvInset(minimumX = 0.dp, minimumY = 0.dp)

/**
 * The layout margins of DESIGN.md › TV layout: [TV_SIDE_MARGIN] and [TV_VERTICAL_MARGIN], or the
 * overscan safe area where a viewport makes that larger, so `tv.overscan` stays the minimum.
 */
internal fun Modifier.tvSafeAreaPadding(): Modifier = tvInset(minimumX = TV_SIDE_MARGIN, minimumY = TV_VERTICAL_MARGIN)

private fun Modifier.tvInset(minimumX: Dp, minimumY: Dp): Modifier = layout { measurable, constraints ->
    check(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
        "the overscan safe area is a fraction of the viewport; it needs bounded constraints"
    }
    val insetX = max(tvOverscanInset(constraints.maxWidth, PutioDesignTokens.tvOverscanX), minimumX.roundToPx())
    val insetY = max(tvOverscanInset(constraints.maxHeight, PutioDesignTokens.tvOverscanY), minimumY.roundToPx())
    val placeable = measurable.measure(constraints.offset(horizontal = -2 * insetX, vertical = -2 * insetY))
    layout(placeable.width + 2 * insetX, placeable.height + 2 * insetY) {
        placeable.place(insetX, insetY)
    }
}

/** Rounded up: a partial pixel of inset still belongs to the unsafe edge. */
private fun tvOverscanInset(viewportPx: Int, ratio: Float): Int = ceil(viewportPx * ratio.toDouble()).toInt()
