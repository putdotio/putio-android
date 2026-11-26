package io.putdotio.android.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val DISABLED_ALPHA = 0.38f

@Composable
public fun PutioSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimensions: PutioSwitchSize = PutioSwitchSize.Phone,
    onTrack: Color = MaterialTheme.colorScheme.onSurface,
    surface: Color = MaterialTheme.colorScheme.surface,
) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, label = "PutioSwitch")
    val solid = PutioDesignTokens.solid
    Spacer(
        modifier
            .size(dimensions.width, dimensions.height)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .drawBehind {
                val outline = dimensions.outline.toPx()
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(color = lerp(surface, onTrack, progress), cornerRadius = radius)
                drawRoundRect(
                    color = lerp(solid, onTrack, progress),
                    topLeft = Offset(outline / 2f, outline / 2f),
                    size = Size(size.width - outline, size.height - outline),
                    cornerRadius = CornerRadius(radius.x - outline / 2f),
                    style = Stroke(outline),
                )
                val offCenter = size.height / 2f
                val onCenter = size.width - size.height / 2f
                drawCircle(
                    color = lerp(solid, surface, progress),
                    radius = lerp(dimensions.offHandle.toPx(), dimensions.onHandle.toPx(), progress) / 2f,
                    center = Offset(lerp(offCenter, onCenter, progress), size.height / 2f),
                )
            },
    )
}

public enum class PutioSwitchSize(
    internal val width: Dp,
    internal val height: Dp,
    internal val outline: Dp,
    internal val offHandle: Dp,
    internal val onHandle: Dp,
) {
    Phone(width = 44.dp, height = 26.dp, outline = 2.dp, offHandle = 12.dp, onHandle = 18.dp),
    Tv(width = 28.dp, height = 16.dp, outline = 1.5.dp, offHandle = 6.dp, onHandle = 10.dp),
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
