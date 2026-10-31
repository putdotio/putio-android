package io.putdotio.android.tv.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvDialog
import io.putdotio.android.tv.tvSafeAreaPadding

@Composable
internal fun TvResumePlaybackDialog(
    title: String,
    startFromSeconds: Double,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().tvSafeAreaPadding()) { TvPlayerTopBar(title) }
    val time = playerTime((startFromSeconds * MILLIS_PER_SECOND).toLong())
    TvDialog(
        title = stringResource(R.string.tv_player_resume_title, time),
        message = null,
        onDismiss = onDismiss,
    ) { focus ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvButton(onClick = onRestart, style = TvButtonStyle.Text) {
                Text(stringResource(R.string.tv_player_start_over))
            }
            TvButton(onClick = onResume, style = TvButtonStyle.Filled, modifier = Modifier.focusRequester(focus)) {
                Text(stringResource(R.string.tv_player_resume))
            }
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
