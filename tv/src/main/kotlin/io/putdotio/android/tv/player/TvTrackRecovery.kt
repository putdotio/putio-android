package io.putdotio.android.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import io.putdotio.android.R
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.PlaybackTrackRecoveryStep
import io.putdotio.android.playback.continueWithout
import io.putdotio.android.tv.TvSnackbarMessage
import io.putdotio.android.tv.TvSnackbarState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSubtitles

internal fun Player.continueWithout(
    step: PlaybackTrackRecoveryStep.DropSubtitles,
    notice: TvSnackbarState,
    message: String,
) {
    continueWithout(step)
    if (step.notify) notice.show(TvSnackbarMessage(message))
}

@Composable
internal fun TvSubtitlesUnavailableNotice(source: PlaybackSource, target: PlaybackTarget, snackbar: TvSnackbarState) {
    val message = stringResource(R.string.tv_player_subtitles_failed)
    val unavailable = target.mediaType == PlaybackMediaType.VIDEO && source.subtitles is PlaybackSubtitles.Unavailable
    LaunchedEffect(source, unavailable) {
        if (unavailable) snackbar.show(TvSnackbarMessage(message))
    }
}
