package io.putdotio.android.tv

import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.putdotio.android.design.PutioDesignTokens
import kotlin.math.ceil
import kotlin.math.max

internal val TV_SIDE_MARGIN = 48.dp
internal val TV_VERTICAL_MARGIN = 24.dp

internal fun Modifier.tvOverscanPadding(): Modifier = tvInset(minimumX = 0.dp, minimumY = 0.dp)

internal fun Modifier.tvSafeAreaPadding(): Modifier =
    tvInset(minimumX = TV_SIDE_MARGIN, minimumY = TV_VERTICAL_MARGIN)
        .recalculateWindowInsets()
        .safeDrawingPadding()

internal data class TvSafeAreaMargins(val side: Dp, val vertical: Dp)

internal fun Density.tvSafeAreaMargins(viewport: Constraints): TvSafeAreaMargins {
    requireBounded(viewport)
    val side = tvSafeInset(viewport.maxWidth, PutioDesignTokens.tvOverscanX, TV_SIDE_MARGIN.roundToPx())
    val vertical = tvSafeInset(viewport.maxHeight, PutioDesignTokens.tvOverscanY, TV_VERTICAL_MARGIN.roundToPx())
    return TvSafeAreaMargins(side = side.toDp(), vertical = vertical.toDp())
}

private fun Modifier.tvInset(minimumX: Dp, minimumY: Dp): Modifier = layout { measurable, constraints ->
    requireBounded(constraints)
    val insetX = tvSafeInset(constraints.maxWidth, PutioDesignTokens.tvOverscanX, minimumX.roundToPx())
    val insetY = tvSafeInset(constraints.maxHeight, PutioDesignTokens.tvOverscanY, minimumY.roundToPx())
    val placeable = measurable.measure(constraints.offset(horizontal = -2 * insetX, vertical = -2 * insetY))
    layout(placeable.width + 2 * insetX, placeable.height + 2 * insetY) {
        placeable.place(insetX, insetY)
    }
}

private fun requireBounded(viewport: Constraints) {
    check(viewport.hasBoundedWidth && viewport.hasBoundedHeight) {
        "the overscan safe area is a fraction of the viewport; it needs bounded constraints"
    }
}

private fun tvSafeInset(viewportPx: Int, ratio: Float, minimumPx: Int): Int =
    max(ceil(viewportPx * ratio.toDouble()).toInt(), minimumPx)
