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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

private val TRACK_WIDTH = 44.dp
private val TRACK_HEIGHT = 26.dp
private val OUTLINE_WIDTH = 2.dp
private val OFF_HANDLE = 12.dp
private val ON_HANDLE = 18.dp

/** Material's disabled-content opacity. */
private const val DISABLED_ALPHA = 0.38f

/**
 * DESIGN.md's compact neutral switch, a deliberate step off stock M3. Off: a `surface` track in a
 * 2dp `--solid` outline with a 12dp `--solid` handle. On: an `on-surface` track with an 18dp
 * `surface` handle.
 *
 * It only draws. The row it sits in toggles as one node, with `Role.Switch` and the checked
 * state, and is the 48dp target, so the switch adds no semantics and takes no input.
 */
@Composable
public fun PutioSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, label = "PutioSwitch")
    val solid = PutioDesignTokens.solid
    val onTrack = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    Spacer(
        modifier
            .size(TRACK_WIDTH, TRACK_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .drawBehind {
                val outline = OUTLINE_WIDTH.toPx()
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(color = lerp(surface, onTrack, progress), cornerRadius = radius)
                drawRoundRect(
                    color = lerp(solid, onTrack, progress),
                    topLeft = Offset(outline / 2f, outline / 2f),
                    size = Size(size.width - outline, size.height - outline),
                    cornerRadius = CornerRadius(radius.x - outline / 2f),
                    style = Stroke(outline),
                )
                // Each handle keeps the gap between it and the outline equal on three sides.
                val offCenter = size.height / 2f
                val onCenter = size.width - size.height / 2f
                drawCircle(
                    color = lerp(solid, surface, progress),
                    radius = lerp(OFF_HANDLE.toPx(), ON_HANDLE.toPx(), progress) / 2f,
                    center = Offset(lerp(offCenter, onCenter, progress), size.height / 2f),
                )
            },
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
