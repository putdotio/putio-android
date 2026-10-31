package io.putdotio.android.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import io.putdotio.android.playback.R as PlaybackR
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvDialog

@Composable
internal fun TvResumePlaybackDialog(
    title: String,
    posterUrl: String?,
    startFromSeconds: Double,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = POSTER_DIM)))
        }
        Box(Modifier.fillMaxSize().tvPlayerEdges()) { TvPlayerTopBar(title) }
    }
    val time = playerTime((startFromSeconds * MILLIS_PER_SECOND).toLong())
    TvDialog(
        title = resumeTitle(stringResource(PlaybackR.string.playback_resume_title, time), time),
        message = null,
        onDismiss = onDismiss,
    ) { focus ->
        val dialog = LocalView.current.parent as? DialogWindowProvider
        SideEffect { dialog?.window?.setDimAmount(0f) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvButton(onClick = onRestart, style = TvButtonStyle.Text) {
                Text(stringResource(PlaybackR.string.playback_start_over), style = BUTTON_LABEL)
            }
            TvButton(onClick = onResume, style = TvButtonStyle.Filled, modifier = Modifier.focusRequester(focus)) {
                Text(stringResource(PlaybackR.string.playback_resume), style = BUTTON_LABEL)
            }
        }
    }
}

private fun resumeTitle(title: String, time: String): AnnotatedString {
    val start = title.indexOf(time)
    if (start < 0) return AnnotatedString(title)
    return buildAnnotatedString {
        append(title)
        addStyle(SpanStyle(fontFamily = PutioTypeScale.tvMono.fontFamily), start, start + time.length)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val POSTER_DIM = 0.65f
private val BUTTON_LABEL = PutioTypeScale.tvBody.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium)
