package io.putdotio.android.playback

import android.content.Context
import android.view.View.MeasureSpec
import android.view.accessibility.CaptioningManager
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
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

public const val SUBTITLE_CUES_TAG: String = "subtitle-cues"

@Composable
@UnstableApi
public fun SubtitleCueOverlay(
    cues: List<Cue>,
    videoAspectRatio: Float?,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    bottomPaddingFraction: Float = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
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
                    .testTag(SUBTITLE_CUES_TAG),
        ) {
            drawIntoCanvas { canvas ->
                renderer.layOut(textScale, bottomPaddingFraction)
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
    context: Context,
) {
    private val subtitleView =
        SubtitleView(context).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
    private val captionScale =
        (context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager)
            ?.takeIf { it.isEnabled }
            ?.fontScale
            ?: 1f
    private var renderedCues: List<Cue> = emptyList()
    private var renderedScale = 1f
    private var renderedBottomPadding = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
    private var renderedWidth = 0
    private var renderedHeight = 0

    /**
     * [textScale] multiplies Android's caption size, as the user's caption settings set it; any
     * scale but 1 also overrides the sizes a styled subtitle file sets. [bottomPaddingFraction]
     * raises cues placed at the bottom by default; positioned cues stay where the file puts them.
     */
    fun layOut(textScale: Float, bottomPaddingFraction: Float) {
        if (renderedBottomPadding != bottomPaddingFraction) {
            renderedBottomPadding = bottomPaddingFraction
            subtitleView.setBottomPaddingFraction(bottomPaddingFraction)
        }
        if (renderedScale != textScale) {
            renderedScale = textScale
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * captionScale * textScale)
            // A size the viewer picked wins over sizes the subtitle file sets.
            subtitleView.setApplyEmbeddedFontSizes(textScale == 1f)
        }
    }

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
                MeasureSpec.makeMeasureSpec(safeWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(safeHeight, MeasureSpec.EXACTLY),
            )
            subtitleView.layout(0, 0, safeWidth, safeHeight)
        }
        subtitleView.draw(canvas)
    }
}

public fun VideoSize.displayAspectRatioOrNull(): Float? =
    when {
        width <= 0 || height <= 0 -> null
        !pixelWidthHeightRatio.isFinite() || pixelWidthHeightRatio <= 0f -> null
        else -> width.toFloat() * pixelWidthHeightRatio / height.toFloat()
    }

private fun Modifier.fitInsideAspectRatio(aspectRatio: Float): Modifier =
    layout { measurable, constraints ->
        val fitted = fitInside(constraints.maxWidth, constraints.maxHeight, aspectRatio)
        val placeable = measurable.measure(Constraints.fixed(fitted.width, fitted.height))
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
