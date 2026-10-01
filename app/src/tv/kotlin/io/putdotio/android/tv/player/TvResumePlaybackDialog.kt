package io.putdotio.android.tv.player

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvDialog

internal const val TV_PLAYER_RESUME_PROGRESS_TAG = "tv-player-resume-progress"

/**
 * The play-time resume choice, as the RN player's (putio-web `apps/tv-native`
 * `withResumePlaybackPrompt.tsx`): the raw file name, a progress bar, and stacked Continue
 * and Start from the beginning buttons. Continue takes focus; the bar previews where the
 * focused choice starts. Back leaves playback, as the RN prompt and mobile's do (#238).
 */
@Composable
internal fun TvResumePlaybackDialog(
    title: String,
    startFromSeconds: Double,
    durationSeconds: Double,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    var restartFocused by remember { mutableStateOf(false) }
    val previewSeconds = if (restartFocused) 0.0 else startFromSeconds
    val fraction = (previewSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
    val progressDescription = stringResource(
        R.string.tv_player_resume_progress,
        previewSeconds.elapsedLabel(),
        durationSeconds.elapsedLabel(),
    )
    TvDialog(
        title = title,
        message = null,
        onDismiss = onDismiss,
        body = {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .height(8.dp)
                    .testTag(TV_PLAYER_RESUME_PROGRESS_TAG)
                    .semantics { contentDescription = progressDescription },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        },
    ) { focus ->
        TvButton(
            onClick = onResume,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { if (it.isFocused) restartFocused = false },
        ) {
            Text(stringResource(R.string.tv_player_resume_continue, startFromSeconds.elapsedLabel()))
        }
        TvButton(
            onClick = onRestart,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) restartFocused = true },
        ) {
            Text(stringResource(R.string.tv_player_resume_restart))
        }
    }
}

private fun Double.elapsedLabel(): String = DateUtils.formatElapsedTime(toLong())
