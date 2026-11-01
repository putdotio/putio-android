package io.putdotio.android.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph

@Composable
internal fun TvStartupScreen(
    message: String,
    modifier: Modifier = Modifier,
) {
    StartupFrame(top = CHECKING_TOP, modifier = modifier) { TvLoadingIndicator(message) }
}

@Composable
internal fun TvLoadingIndicator(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SPINNER_GAP, Alignment.CenterVertically),
    ) {
        TvSpinner(Modifier.size(SPINNER_SIZE))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.withFullLineBox(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TvSpinner(modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(SPINNER_TURN_MILLIS, easing = LinearEasing)),
        label = "turn",
    )
    val arc = MaterialTheme.colorScheme.primary
    val track = PutioDesignTokens.componentBgActive
    Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val stroke = SPINNER_STROKE.toPx()
        val inset = stroke / 2
        val ring = Size(size.width - stroke, size.height - stroke)
        val origin = Offset(inset, inset)
        drawArc(track, 0f, FULL_TURN, useCenter = false, topLeft = origin, size = ring, style = Stroke(stroke))
        drawArc(
            color = arc,
            startAngle = ARC_START + turn,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = origin,
            size = ring,
            style = Stroke(stroke, cap = StrokeCap.Butt),
        )
    }
}

@Composable
internal fun TvOfflineScreen(
    onTryNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StartupFrame(top = EMPTY_STATE_TOP, modifier = modifier) {
        TvEmptyState(
            glyph = PutioEmptyGlyph.Offline,
            headline = stringResource(R.string.tv_session_unavailable_title),
            message = stringResource(R.string.tv_session_unavailable_message),
            action = TvEmptyStateAction(
                label = stringResource(R.string.tv_session_retry),
                onClick = onTryNow,
                icon = R.drawable.ic_ph_arrows_clockwise,
            ),
        )
    }
}

@Composable
private fun StartupFrame(
    top: Dp,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvWordmark(Modifier.padding(top = TV_VERTICAL_MARGIN))
        Box(Modifier.fillMaxSize().padding(top = top, bottom = CONTENT_BOTTOM)) { content() }
    }
}

private val CHECKING_TOP = 64.dp
private val EMPTY_STATE_TOP = 88.dp
private val CONTENT_BOTTOM = 27.dp
private val SPINNER_GAP = 14.dp
private val SPINNER_SIZE = 28.dp
private val SPINNER_STROKE = 2.5.dp
private const val SPINNER_TURN_MILLIS = 1_000
private const val FULL_TURN = 360f
private const val ARC_START = -135f
private const val ARC_SWEEP = 90f
