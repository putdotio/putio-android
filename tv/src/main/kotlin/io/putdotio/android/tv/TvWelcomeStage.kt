package io.putdotio.android.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.rememberAnimationsOff
import io.putdotio.android.design.rememberWelcomeSequencePosition
import io.putdotio.android.design.R as DesignR

private val TvWelcomeSequence: List<Pair<Int, Int>> = listOf(
    R.string.tv_welcome_kaomoji_magnet to R.string.tv_welcome_caption_magnet,
    R.string.tv_welcome_kaomoji_fetch to R.string.tv_welcome_caption_fetch,
    R.string.tv_welcome_kaomoji_stream to R.string.tv_welcome_caption_stream,
    R.string.tv_welcome_kaomoji_offline to R.string.tv_welcome_caption_offline,
)

private val FRAME_HEIGHT = 64.dp
private val KAOMOJI_LINE = 42.dp

@Composable
internal fun TvWordmark(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(WORDMARK_BAR), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(DesignR.drawable.putio_wordmark),
            contentDescription = stringResource(R.string.tv_wordmark),
            modifier = Modifier.height(WORDMARK_HEIGHT).aspectRatio(WORDMARK_ASPECT_RATIO),
        )
    }
}

@Composable
internal fun TvWelcomeStage(modifier: Modifier = Modifier) {
    val frames = TvWelcomeSequence
    val position = rememberWelcomeSequencePosition(frames, animates = !rememberAnimationsOff())
    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(FRAME_HEIGHT).clipToBounds()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .graphicsLayer { translationY = -position.floatValue * FRAME_HEIGHT.toPx() },
            ) {
                (frames + frames.first()).forEach { frame ->
                    WelcomeFrame(frame = frame, modifier = Modifier.fillMaxWidth().height(FRAME_HEIGHT))
                }
            }
        }
    }
}

@Composable
private fun WelcomeFrame(
    frame: Pair<Int, Int>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Top),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = stringResource(frame.first),
            style = MaterialTheme.typography.titleLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 36.sp,
                lineHeight = 42.sp,
                letterSpacing = 0.sp,
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.height(KAOMOJI_LINE).wrapContentHeight(unbounded = true),
        )
        Text(
            text = stringResource(frame.second),
            style = PutioTypeScale.tvMono.copy(fontSize = 13.sp, lineHeight = 16.sp),
            color = PutioDesignTokens.yellowSolid,
        )
    }
}

private val WORDMARK_BAR = 40.dp
private val WORDMARK_HEIGHT = 22.dp
private const val WORDMARK_ASPECT_RATIO = 376f / 112f
