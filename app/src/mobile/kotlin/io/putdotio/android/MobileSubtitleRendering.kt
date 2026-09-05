package io.putdotio.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

internal const val MOBILE_SUBTITLE_CUES_TAG = "mobile-subtitle-cues"

@Composable
@UnstableApi
internal fun MobileSubtitleCueOverlay(
    cues: List<Cue>,
    videoAspectRatio: Float?,
    modifier: Modifier = Modifier,
) {
    if (cues.isEmpty()) return
    // Replacement players can deliver retained cues before their video dimensions.
    if (videoAspectRatio == null || !videoAspectRatio.isFinite() || videoAspectRatio <= 0f) return
    val context = LocalContext.current
    val renderer = remember(context) { SubtitleCueRenderer(context) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier =
                Modifier
                    .fitInsideAspectRatio(videoAspectRatio)
                    .testTag(MOBILE_SUBTITLE_CUES_TAG),
        ) {
            drawIntoCanvas { canvas ->
                renderer.draw(
                    cues = cues,
                    width = size.width.roundToInt(),
                    height = size.height.roundToInt(),
                    canvas = canvas.nativeCanvas,
                )
            }
        }
    }
}

@UnstableApi
internal class SubtitleCueRenderer(
    context: android.content.Context,
) {
    private val subtitleView =
        SubtitleView(context).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
    private var renderedCues: List<Cue> = emptyList()
    private var renderedWidth = 0
    private var renderedHeight = 0

    fun draw(
        cues: List<Cue>,
        width: Int,
        height: Int,
        canvas: android.graphics.Canvas,
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (renderedCues != cues) {
            renderedCues = cues
            subtitleView.setCues(cues)
        }
        if (renderedWidth != safeWidth || renderedHeight != safeHeight) {
            renderedWidth = safeWidth
            renderedHeight = safeHeight
            subtitleView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(safeWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(safeHeight, android.view.View.MeasureSpec.EXACTLY),
            )
            subtitleView.layout(0, 0, safeWidth, safeHeight)
        }
        subtitleView.draw(canvas)
    }
}

internal fun VideoSize.displayAspectRatioOrNull(): Float? =
    when {
        width <= 0 || height <= 0 -> null
        !pixelWidthHeightRatio.isFinite() || pixelWidthHeightRatio <= 0f -> null
        else -> width.toFloat() * pixelWidthHeightRatio / height.toFloat()
    }

private fun Modifier.fitInsideAspectRatio(aspectRatio: Float): Modifier =
    layout { measurable, constraints ->
        val fitted = fitInside(constraints.maxWidth, constraints.maxHeight, aspectRatio)
        val placeable =
            measurable.measure(
                androidx.compose.ui.unit.Constraints.fixed(fitted.width, fitted.height),
            )
        layout(fitted.width, fitted.height) { placeable.place(0, 0) }
    }

internal data class FittedVideoSize(val width: Int, val height: Int)

internal fun fitInside(
    availableWidth: Int,
    availableHeight: Int,
    aspectRatio: Float,
): FittedVideoSize {
    if (availableWidth <= 0 || availableHeight <= 0) return FittedVideoSize(0, 0)
    val availableRatio = availableWidth.toFloat() / availableHeight.toFloat()
    return if (aspectRatio >= availableRatio) {
        FittedVideoSize(availableWidth, (availableWidth / aspectRatio).roundToInt())
    } else {
        FittedVideoSize((availableHeight * aspectRatio).roundToInt(), availableHeight)
    }
}
