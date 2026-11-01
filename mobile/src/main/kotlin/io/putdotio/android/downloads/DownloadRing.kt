package io.putdotio.android.downloads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens

internal const val MOBILE_DOWNLOADS_RING_TAG = "mobile-downloads-ring"

/** What a tap on a row's ring does; its glyph shows the action and its colour whether bytes move. */
internal enum class DownloadRingAction { Pause, Resume, Cancel }

/** The ring a Downloading row trails with, or null for a row that shows none. */
internal fun DownloadEntry.ringAction(): DownloadRingAction? =
    when (val value = status) {
        DownloadStatus.Queued -> DownloadRingAction.Cancel
        is DownloadStatus.Downloading -> DownloadRingAction.Pause
        is DownloadStatus.Paused ->
            if (value.reason == DownloadPauseReason.USER) DownloadRingAction.Resume else DownloadRingAction.Pause
        else -> null
    }

@Composable
internal fun DownloadRing(status: DownloadStatus, action: DownloadRingAction, onClick: () -> Unit) {
    val label = stringResource(
        when (action) {
            DownloadRingAction.Pause -> R.string.mobile_downloads_ring_pause
            DownloadRingAction.Resume -> R.string.mobile_downloads_ring_resume
            DownloadRingAction.Cancel -> R.string.mobile_downloads_ring_cancel
        },
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val glyph = MaterialTheme.colorScheme.onSurface
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label }.testTag(MOBILE_DOWNLOADS_RING_TAG),
    ) {
        Box(Modifier.size(RING_BOX), contentAlignment = Alignment.Center) {
            when (status) {
                is DownloadStatus.Downloading -> RingProgress(status.percent, PutioDesignTokens.yellowSolid, track)
                is DownloadStatus.Paused ->
                    RingProgress(status.percent ?: 0f, MaterialTheme.colorScheme.onSurfaceVariant, track)
                else -> Canvas(Modifier.size(RING_BOX)) { drawDashedTrack(track) }
            }
            Canvas(Modifier.size(RING_BOX)) {
                when (action) {
                    DownloadRingAction.Pause -> drawPauseBars(glyph)
                    DownloadRingAction.Resume -> drawPlayTriangle(glyph)
                    DownloadRingAction.Cancel -> drawStopSquare(glyph)
                }
            }
        }
    }
}

/** Material's indicator, drawn on the ring's 26dp stroke circle; no percentage yet spins. */
@Composable
private fun RingProgress(percent: Float?, color: Color, track: Color) {
    val modifier = Modifier.size(RING_DIAMETER + RING_STROKE)
    if (percent == null) {
        CircularProgressIndicator(
            modifier = modifier,
            color = color,
            strokeWidth = RING_STROKE,
            trackColor = track,
            strokeCap = StrokeCap.Round,
        )
    } else {
        CircularProgressIndicator(
            progress = { (percent / PERCENT).coerceIn(0f, 1f) },
            modifier = modifier,
            color = color,
            strokeWidth = RING_STROKE,
            trackColor = track,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    }
}

// Glyph geometry in the board's 36-unit ring box.
private fun DrawScope.unit(value: Float): Float = value * size.width / RING_UNITS

private fun DrawScope.drawDashedTrack(color: Color) {
    drawCircle(
        color = color,
        radius = unit(RING_RADIUS_UNITS),
        style = Stroke(
            width = unit(STROKE_UNITS),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(unit(DASH_UNITS), unit(GAP_UNITS))),
        ),
    )
}

private fun DrawScope.drawPauseBars(color: Color) {
    for (left in floatArrayOf(PAUSE_LEFT_UNITS, PAUSE_RIGHT_UNITS)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(unit(left), unit(PAUSE_TOP_UNITS)),
            size = Size(unit(PAUSE_WIDTH_UNITS), unit(PAUSE_HEIGHT_UNITS)),
            cornerRadius = CornerRadius(unit(1f)),
        )
    }
}

private fun DrawScope.drawPlayTriangle(color: Color) {
    val path = Path().apply {
        moveTo(unit(PLAY_LEFT_UNITS), unit(PLAY_TOP_UNITS))
        lineTo(unit(PLAY_RIGHT_UNITS), unit(RING_UNITS / 2))
        lineTo(unit(PLAY_LEFT_UNITS), unit(RING_UNITS - PLAY_TOP_UNITS))
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawStopSquare(color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(unit(STOP_START_UNITS), unit(STOP_START_UNITS)),
        size = Size(unit(STOP_SIZE_UNITS), unit(STOP_SIZE_UNITS)),
        cornerRadius = CornerRadius(unit(STOP_CORNER_UNITS)),
    )
}

private val RING_BOX = 36.dp
private val RING_DIAMETER = 26.dp
private val RING_STROKE = 3.dp
private const val PERCENT = 100f
private const val RING_UNITS = 36f
private const val RING_RADIUS_UNITS = 13f
private const val STROKE_UNITS = 3f
private const val DASH_UNITS = 3f
private const val GAP_UNITS = 4f
private const val PAUSE_LEFT_UNITS = 13.5f
private const val PAUSE_RIGHT_UNITS = 19.5f
private const val PAUSE_TOP_UNITS = 13f
private const val PAUSE_WIDTH_UNITS = 3f
private const val PAUSE_HEIGHT_UNITS = 10f
private const val PLAY_LEFT_UNITS = 15f
private const val PLAY_RIGHT_UNITS = 24f
private const val PLAY_TOP_UNITS = 12.5f
private const val STOP_START_UNITS = 14f
private const val STOP_SIZE_UNITS = 8f
private const val STOP_CORNER_UNITS = 1.5f
